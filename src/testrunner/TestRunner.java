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
    private String PREFIX_ERROR = (char) 27 + "[91mERROR: ";
    private String PREFIX_WARN = (char) 27 + "[95mWARN: ";
    private String SUFFIX_CLEAR = (char) 27 + "[0m";
    private TestScheduler scheduler;

    public TestRunner(TestScheduler scheduler) {
        this.scheduler = scheduler;
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

    public void run(String agentUrl, File baseFldr) throws Exception {
        int nThreads = Math.min(64, Runtime.getRuntime().availableProcessors() * 8);
        ScheduledExecutorService executor = Executors.newScheduledThreadPool(nThreads);

        ScheduledExecutorService executor2 = Executors.newScheduledThreadPool(1);

        AgentConnection agent = new AgentConnection(agentUrl, true, executor2);
        agent.scheduleStatusCheck();

        List<Future<JobResult>> futures = new ArrayList<Future<JobResult>>();
        scheduleJobs(agent, scheduler, baseFldr, executor, futures);

        System.out.println("INFO: Waiting for the jobs.");
        // Wait for everything to be processed
        List<JobResult> results = new ArrayList<JobResult>();
        for (Future<JobResult> job : futures) {
            JobResult e = job.get();
            if (e != null)
                results.add(e);
        }

        agent.shutdown();

        scheduler.finish(results, baseFldr);

        executor.shutdown();
        executor2.shutdown();
        executor.awaitTermination(Integer.MAX_VALUE, TimeUnit.SECONDS);
    }

    private void scheduleJobs(AgentConnection agent, TestScheduler scheduler, File baseFldr,
            ScheduledExecutorService executor, List<Future<JobResult>> results) throws IOException {
        File requestsFldr = new File(baseFldr, "requests");
        File resultsFldr = new File(baseFldr, "results");
        if (requestsFldr.exists())
            FileUtils.forceDelete(requestsFldr);
        if (resultsFldr.exists())
            FileUtils.forceDelete(resultsFldr);
        requestsFldr.mkdirs();
        resultsFldr.mkdirs();

        List<JobRequest> requests = scheduler.generateJobRequests(requestsFldr);
        for (JobRequest jobRequest : requests) {
            Future<JobResult> future = Util.compoundFuture5(executor.submit(() -> {
                scheduler.createJobArchive(jobRequest);
                Callable<Future<Future<Future<JobResult>>>> task = new Callable<Future<Future<Future<JobResult>>>>() {
                    int retries0 = 0;

                    public Future<Future<Future<JobResult>>> call() {
                        try {
                            RemoteJob rj = agent.scheduleJob(jobRequest.getJobName(), jobRequest.getJobArchive());
                            if (rj == null) {
                                return Util.dummyFuture3(new JobResult(jobRequest, false, ""));
                            }
                            System.out.println("INFO: [" + jobRequest.getJobName() + "] Scheduled.");
                            return rj.onDone(() -> {
                                Callable<Future<JobResult>> task2 = new Callable<Future<JobResult>>() {
                                    int retries1 = 0;

                                    public Future<JobResult> call() {
                                        try {
                                            return Util.dummyFuture(processJobResult(jobRequest, rj, resultsFldr));
                                        } catch (Exception e) {
                                            if (retries1 > MAX_RETRIES) {
                                                System.out.println(PREFIX_ERROR + "[" + jobRequest.getJobName()
                                                        + "] Couldn't schedule a job at all after " + MAX_RETRIES
                                                        + " retries." + SUFFIX_CLEAR);
                                                return Util.dummyFuture(new JobResult(jobRequest, false, ""));
                                            }
                                            ++retries1;
                                            int retryTime = (int) (Math.random() * 10000);
                                            System.out.println(PREFIX_WARN + "[" + jobRequest.getJobName()
                                                    + "] Couldn't get a job result, retrying in " + retryTime + "ms."
                                                    + SUFFIX_CLEAR);
                                            return Util.compoundFuture2(
                                                    executor.schedule(this, retryTime, TimeUnit.MILLISECONDS));
                                        }
                                    }
                                };
                                return executor.submit(task2);
                            });
                        } catch (Exception e) {
                            if (retries0 > MAX_RETRIES) {
                                System.out.println(PREFIX_ERROR + "[" + jobRequest.getJobName()
                                        + "] Couldn't schedule a job at all after " + MAX_RETRIES + " retries."
                                        + SUFFIX_CLEAR);
                                return Util.dummyFuture3(new JobResult(jobRequest, false, ""));
                            }
                            ++retries0;
                            int retryTime = (int) (Math.random() * 10000);
                            System.out.println(PREFIX_WARN + "[" + jobRequest.getJobName()
                                    + "] Couldn't start a job, retrying in " + retryTime + "ms." + SUFFIX_CLEAR);
                            return Util.compoundFuture2(executor.schedule(this, retryTime, TimeUnit.MILLISECONDS));
                        }
                    }
                };
                return executor.submit(task);
            }));
            results.add(future);
        }
    }

    private JobResult processJobResult(JobRequest jobRequest, RemoteJob job, File resultsFldr) throws IOException {
        if (job == null)
            return new JobResult(jobRequest, false, "Remote job was null");
        System.out.println("INFO: [" + jobRequest.getJobName() + "] Processing result.");
        File resultArchive = job.getResultArchive();

        if (resultArchive == null)
            return new JobResult(jobRequest, false, "Result archive was null");

        File dest = new File(resultsFldr, jobRequest.getJobName() + ".zip");
        resultArchive.renameTo(dest);
        resultArchive = dest;

        return scheduler.processResult(jobRequest, resultArchive);
    }
}
