package testrunner;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.apache.commons.io.FileUtils;

import testrunner.HttpIface.HttpIfaceException;
import testrunner.TestScheduler.JobRequest;
import testrunner.TestScheduler.JobResult;

/**
 * Schedules encoder Test
 * 
 * @author vitvitskyy
 *
 */
public class TestRunner {
    private static final int MAX_RETRIES = 100;
    private TestScheduler scheduler;
    private HttpIface http;

    public TestRunner(TestScheduler scheduler) throws HttpIfaceException {
        this.scheduler = scheduler;
        this.http = new HttpIface(1000 /* connectionTimeout */, 20000 /* socketTimeout */);
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.out.println("Syntax: scheduler <agent url> <base folder> <scheduler>");
            return;
        }
        String schedulerName = args[2];
        TestScheduler scheduler = null;
        if ("compare".equals(schedulerName)) {
            scheduler = CompareScheduler.create(Arrays.copyOfRange(args, 3, args.length));
        } else if ("regression".equals(schedulerName)) {
            scheduler = RegressionScheduler.create(Arrays.copyOfRange(args, 3, args.length));
        } else {
            System.out.println("Unknown scheduler: '" + schedulerName + "'");
        }
        if (scheduler == null)
            return;

        new TestRunner(scheduler).run(args[0], new File(args[1]));
    }

    public static class JobOverviewThread extends Thread {
        private List<Job> jobs;

        public JobOverviewThread(List<Job> jobs) {
            this.jobs = jobs;
        }

        @Override
        public void run() {
            int maxLines = 10;
            for (int i = 0; i < 10; i++) {
                System.out.println(((char) 27) + "[K");
            }
            System.out.print(((char) 27) + "[10A");

            boolean allDone = false;
            while (!allDone) {
                int lines = 0;
                allDone = true;
                for (Job job : jobs) {
                    if (job.switchedState()) {
                        if (job.state == JobState.ERROR) {
                            ++maxLines;
                        }
                        if (lines < maxLines && job.state != JobState.DONE) {
                            System.out.print(((char) 27) + "[K");
                            System.out.print(((char) 27) + "[" + stateToColor(job.state) + "m");
                            System.out.println("  " + job.getDescription()
                                    + (job.extraInfo != null ? "(" + job.extraInfo + ")" : ""));
                            ++lines;
                        }
                    }
                    allDone &= job.isDone();
                }
                for (int i = lines; i < maxLines; i++) {
                    System.out.println(((char) 27) + "[K");
                }
                System.out.print(((char) 27) + "[" + maxLines + "A");
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                }
            }
            for (int i = 0; i < maxLines; i++) {
                System.out.println(((char) 27) + "[K");
            }
            System.out.print(((char) 27) + "[" + maxLines + "A");
            System.out.print(((char) 27) + "[0m");
        }

        private int stateToColor(JobState state) {
            switch (state) {
            case INIT:
                return 96;
            case RETRYING:
                return 95;
            case RETRY:
                return 95;
            case READY:
                return 93;
            case RUNNING:
                return 92;
            case PROCESSED:
                return 32;
            case DONE:
                return 32;
            case ERROR:
                return 31;
            default:
                return 0;
            }
        }
    };

    public void run(String agentUrl, File baseFldr) throws Exception {
        int nThreads = Math.min(64, Runtime.getRuntime().availableProcessors() * 8);
        ScheduledExecutorService executor = Executors.newScheduledThreadPool(nThreads);

        ScheduledExecutorService executor2 = Executors.newScheduledThreadPool(1);

        AgentConnection agent = new AgentConnection(agentUrl, true, executor2, http);
        agent.scheduleStatusCheck();

        scheduler.init(baseFldr);

        List<Job> jobs = scheduleJobs(agent, scheduler, baseFldr, executor);
        new JobOverviewThread(jobs).start();

        // Main loop
        Log.info("Waiting for the jobs.");
        List<JobResult> results = new ArrayList<JobResult>();
        boolean allDone = false;
        while (!allDone) {
            allDone = true;
            for (Job job : jobs) {
                //if (job.state == JobState.READY) job.ready = true;
                if (!job.isDone() && job.isReady()) {
                    job.iterate(executor);
                }

                allDone &= job.isDone();
            }

            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
            }
        }

        for (Job job : jobs) {
            results.add(job.getResult());
        }

        agent.shutdown();

        scheduler.finish(results);

        executor.shutdown();
        executor2.shutdown();
        executor.awaitTermination(Integer.MAX_VALUE, TimeUnit.SECONDS);
    }

    private enum JobState {
        INIT, RETRYING, RETRY, READY, RUNNING, PROCESSED, DONE, ERROR
    };

    private class Job implements Runnable {
        AgentConnection agent;
        RemoteJob rj;
        JobRequest req;
        volatile JobState state;
        volatile boolean ready;
        long timeWhenReady;
        int retries;
        File resultsFldr;
        String errorDesc;
        private JobResult result;
        private JobState savedState;
        private String extraInfo;

        public Job(AgentConnection agent, JobRequest jobRequest, File resultsFldr) {
            this.agent = agent;
            this.req = jobRequest;
            this.resultsFldr = resultsFldr;
            this.ready = true;
            this.state = JobState.INIT;
            this.timeWhenReady = 0;
        }

        public boolean switchedState() {
            boolean result = savedState != state;
            savedState = state;
            return result;
        }

        public String getDescription() {
            return req.getJobName() + ":" + state;
        }

        public JobResult getResult() {
            return result;
        }

        @Override
        public synchronized void run() {
            try {
                switch (state) {
                case INIT:
                    scheduler.createJobArchive(req);
                    state = JobState.READY;
                    break;
                case RETRY:
                    if (System.currentTimeMillis() > timeWhenReady) {
                        extraInfo = null;
                        state = JobState.READY;
                    }
                    break;
                case RETRYING:
                    if (retries > MAX_RETRIES) {
                        extraInfo = "Couldn't schedule a job at all after " + MAX_RETRIES + " retries.";
                        state = JobState.ERROR;
                    } else {
                        long retryTime = (long) (Math.random() * 10000);
                        timeWhenReady = System.currentTimeMillis() + retryTime;
                        ++retries;
                        extraInfo = errorDesc + ", retrying in " + retryTime + "ms.";
                        state = JobState.RETRY;
                    }
                    break;
                case READY:
                    try {
                        rj = agent.scheduleJob(req.getJobName(), req.getJobArchive(), req.getPriority());

                        if (rj == null) {
                            state = JobState.ERROR;
                        } else if (rj == RemoteJob.WAIT) {
                            errorDesc = "remote agent is too busy";
                            state = JobState.RETRYING;
                        } else {
                            Log.info("[" + req.getJobName() + "] Scheduled.");
                            state = JobState.RUNNING;
                        }
                    } catch (IOException | HttpIfaceException e1) {
                        errorDesc = "couldn't schedule with remote agent (" + e1.getMessage() + ")";
                        state = JobState.RETRYING;
                    }
                    break;
                case RUNNING:
                    state = rj.isFinished() ? JobState.PROCESSED : JobState.RUNNING;
                    break;
                case PROCESSED:
                    try {
                        result = processJobResult(req, rj, resultsFldr);
                        state = JobState.DONE;
                    } catch (Exception e) {
                        errorDesc = "couldn't process job result (" + e.getMessage() + "), rerunning the whole job";
                        state = JobState.RETRYING;
                    }
                    break;
                case ERROR:
                    break;
                case DONE:
                    break;
                }
            } finally {
                ready = true;
            }
        }

        public boolean isReady() {
            return ready;
        }

        public boolean isDone() {
            return state == JobState.DONE || state == JobState.ERROR;
        }

        public void iterate(ScheduledExecutorService executor) {
            ready = false;
            executor.execute(this);
        }
    }

    private List<Job> scheduleJobs(AgentConnection agent, TestScheduler scheduler, File baseFldr,
            ScheduledExecutorService executor) throws IOException {
        File requestsFldr = new File(baseFldr, "requests");
        File resultsFldr = new File(baseFldr, "results");
        if (requestsFldr.exists())
            FileUtils.forceDelete(requestsFldr);
        if (resultsFldr.exists())
            FileUtils.forceDelete(resultsFldr);
        requestsFldr.mkdirs();
        resultsFldr.mkdirs();

        List<Job> jobs = new ArrayList<Job>();
        List<JobRequest> requests = scheduler.generateJobRequests(requestsFldr);
        for (JobRequest jobRequest : requests) {
            jobs.add(new Job(agent, jobRequest, resultsFldr));
        }
        return jobs;
    }

    private JobResult processJobResult(JobRequest jobRequest, RemoteJob job, File resultsFldr)
            throws IOException, HttpIfaceException {
        if (job == null)
            return new JobResult(jobRequest, false, "Remote job was null");
        if (job.getStatus() == BaseJob.Status.DONE) {
            Log.info("[" + jobRequest.getJobName() + "] Processing result.");
            File resultArchive = job.getResultArchive(http);

            if (resultArchive == null)
                return new JobResult(jobRequest, false, "Result archive was null");

            File dest = new File(resultsFldr, jobRequest.getJobName() + ".zip");
            resultArchive.renameTo(dest);
            resultArchive = dest;

            JobResult processResult = scheduler.processResult(jobRequest, resultArchive);
            if (processResult.isValid()) {
                // everything went well, nothing to see there
                jobRequest.getJobArchive().delete();
            }
            return processResult;
        } else {
            scheduler.processError(jobRequest);
            return new JobResult(jobRequest, false, "");
        }
    }
}
