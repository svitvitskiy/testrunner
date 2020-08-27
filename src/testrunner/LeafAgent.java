package testrunner;

import java.io.File;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import testrunner.BaseJob.Status;

/**
 * Agent to run at specific worker
 * 
 * @author vitvitskyy
 *
 */
public class LeafAgent extends BaseAgent {
    private List<BaseJob> jobs = new LinkedList<BaseJob>();
    private FileStore files;
    private ExecutorService executor;
    private LeafJob.JobFactory jobFactory;

    private LeafAgent(File baseDir) {
        int nThreads = Runtime.getRuntime().availableProcessors();
        executor = Executors.newFixedThreadPool(nThreads);
        files = new FileStore(new File(baseDir, "store"));
        jobFactory = new LeafJob.JobFactory(files, new File(baseDir, "processing"));
        System.out.println("Starting agent with " + nThreads + " threads.");

        ScheduledExecutorService tp = Executors.newScheduledThreadPool(1);
        Runnable runnable = new Runnable() {
            @Override
            public void run() {
                for (BaseJob baseJob : safeCopy(jobs)) {
                    if (baseJob.getStatus() == Status.NEW) {
                        runJob((LeafJob) baseJob, executor);
                    }
                }
            }
        };

        tp.scheduleAtFixedRate(runnable, 100, 100, TimeUnit.MILLISECONDS);
    }

    public void runJob(LeafJob job, ExecutorService executor) {
        job.updateStatus(Status.PENDING);
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    job.updateStatus(Status.PROCESSING);
                    job.run();
                    job.updateStatus(Status.DONE);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
    }

    @Override
    protected Handler getStatusHandler() {
        return new StatusHandler(jobs);
    }

    @Override
    protected Handler getUploadHandler() {
        return new UploadHandler(files);
    }

    @Override
    protected Handler getDownloadHandler() {
        return new DownloadHandler(files);
    }

    @Override
    protected Handler getNewJobHandler() {
        return new NewJobHandler(jobFactory, jobs, files);
    }
    
    @Override
    protected Handler getStatusPage() {
        return new LeafStatusPage(jobs);
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("Syntax: leafagent <port> <base dir>");
            return;
        }
        int port = Integer.parseInt(args[0]);
        File baseDir = new File(args[1]);
        new LeafAgent(baseDir).startAgent(port);
    }
}
