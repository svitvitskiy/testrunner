package testrunner;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.apache.commons.io.FileUtils;

import testrunner.TestScheduler.JobRequest;
import testrunner.TestScheduler.JobResult;
import testrunner.Util._4Future;

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
        ExecutorService executor = Executors.newFixedThreadPool(nThreads);

        ScheduledExecutorService executor2 = Executors.newScheduledThreadPool(1);

        AgentConnection agent = new AgentConnection(agentUrl, executor2);
        agent.scheduleStatusCheck();

        List<_4Future<JobResult>> futures = new ArrayList<_4Future<JobResult>>();
        scheduleJobs(agent, scheduler, baseFldr, executor, futures);

        System.out.println("INFO: Waiting for the jobs.");
        // Wait for everything to be processed
        List<JobResult> results = new ArrayList<JobResult>();
        for (_4Future<JobResult> job : futures) {
            results.add(job.get());
        }

        agent.shutdown();

        scheduler.finish(results, baseFldr);

        executor.shutdown();
        executor2.shutdown();
        executor.awaitTermination(Integer.MAX_VALUE, TimeUnit.SECONDS);
    }

    private void scheduleJobs(AgentConnection agent, TestScheduler scheduler, File baseFldr, ExecutorService executor,
            List<_4Future<JobResult>> results) throws IOException {
        File requestsFldr = new File(baseFldr, "requests");
        File resultsFldr = new File(baseFldr, "results");
        FileUtils.forceDelete(requestsFldr);
        FileUtils.forceDelete(resultsFldr);
        requestsFldr.mkdirs();
        resultsFldr.mkdirs();

        List<JobRequest> requests = scheduler.generateJobRequests(requestsFldr);
        for (JobRequest jobRequest : requests) {
            _4Future<JobResult> future = Util.compoundFuture(executor.submit(() -> {
                scheduler.createJobArchive(jobRequest);
                return executor.submit(() -> {
                    RemoteJob rj = scheduleWithRetry(agent, jobRequest);
                    return rj.onDone(() -> executor.submit(() -> processJobResult(jobRequest, rj, resultsFldr)));
                });
            }));
            results.add(future);
        }
    }

    private RemoteJob scheduleWithRetry(AgentConnection agent, JobRequest jobRequest) {
        for (int i = 0; i < MAX_RETRIES; i++) {
            try {
                RemoteJob rj = agent.scheduleJob(jobRequest.getJobName(), jobRequest.getJobArchive());
                System.out.println("INFO: [" + jobRequest.getJobName() + "] Scheduled.");
                return rj;
            } catch (IOException e) {
                int retryTime = (int) (Math.random() * 1000);
                System.out.println(PREFIX_WARN + "[" + jobRequest.getJobName() + "] Couldn't start a job, retrying in "
                        + retryTime + "ms." + SUFFIX_CLEAR);
                // Random holdoff
                try {
                    Thread.sleep(retryTime);
                } catch (InterruptedException e1) {
                }
            }
        }
        System.out.println(PREFIX_ERROR + "[" + jobRequest.getJobName() + "] Couldn't schedule a job at all after "
                + MAX_RETRIES + " retries." + SUFFIX_CLEAR);
        return null;
    }

    private JobResult processJobResult(JobRequest jobRequest, RemoteJob job, File resultsFldr) {
        if (job == null)
            return new JobResult(jobRequest, false, "Remote job was null");
        System.out.println("INFO: [" + jobRequest.getJobName() + "] Processing result.");
        File resultArchive = getResultWithRetry(job);

        if (resultArchive == null)
            return new JobResult(jobRequest, false, "Result archive was null");

        File dest = new File(resultsFldr, jobRequest.getJobName() + ".zip");
        resultArchive.renameTo(dest);
        resultArchive = dest;

        return scheduler.processResult(jobRequest, resultArchive);
    }

    private File getResultWithRetry(RemoteJob job) {
        for (int i = 0; i < MAX_RETRIES; i++) {
            try {
                return job.getResultArchive();
            } catch (IOException e) {
                int retryTime = (int) (Math.random() * 1000);
                System.out.println(PREFIX_WARN + "[" + job.getName() + "] Couldn't get a job result, retrying in "
                        + retryTime + "ms." + SUFFIX_CLEAR);
                // Random holdoff
                try {
                    Thread.sleep(retryTime);
                } catch (InterruptedException e1) {
                }
            }
        }
        System.out.println(PREFIX_ERROR + "[" + job.getName() + "] Couldn't get a result at all after " + MAX_RETRIES
                + " retries." + SUFFIX_CLEAR);
        return null;
    }
}
