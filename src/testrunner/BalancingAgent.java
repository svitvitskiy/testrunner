package testrunner;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.apache.commons.io.FileUtils;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * This agent is going to balance the tasks between the subordinate agents
 * 
 * @author vitvitskyy
 *
 */
public class BalancingAgent extends BaseAgent implements BaseJob.JobFactory {
    private ScheduledExecutorService executor;
    private List<BaseJob> jobs = new LinkedList<BaseJob>();
    private List<AgentConnection> delegates = new LinkedList<AgentConnection>();
    private FileStore files;
    private List<String> delegateUrls;
    private String myUrl;
    private long startTime;
    private HttpIface http;

    public BalancingAgent(File agentBase, List<String> delegateUrls, String myUrl) {
        this.myUrl = myUrl;
        int nThreads = Runtime.getRuntime().availableProcessors();
        this.executor = Executors.newScheduledThreadPool(Math.min(64, nThreads * 8));
        files = new FileStore(new File(agentBase, "store"));
        this.delegateUrls = delegateUrls;
        this.http = new HttpIface(1000 /*connectionTimeout*/, 20000 /*socketTimeout*/);
        this.startTime = System.currentTimeMillis();
    }

    private void doBalancing() throws IOException {
        List<AgentConnection> tmp = Util.safeCopy(delegates);
        List<BaseJob> safeCopy = Util.safeCopy(jobs);
        Log.debug("Trying to balance " + safeCopy.size() + " jobs.");
        for (BaseJob baseJob : safeCopy) {
            BalancingJob bj = (BalancingJob) baseJob;
            if (!bj.hasDelegate()) {
                RemoteJob remoteJob = tryDelegate(bj, tmp);
                if (remoteJob != null) {
                    bj.updateDelegate(remoteJob);
                    Log.info("[" + bj.getName() + "] Scheduled job with remote agent '" + remoteJob.getAgent().getUrl()
                            + "'.");
                }
            }
        }
    }

    private RemoteJob tryDelegate(BalancingJob job, List<AgentConnection> tmp) throws IOException {
        Log.debug("[" + job.getName() + "] Trying to balance");
        int bestCapacity = 0;
        AgentConnection bestDelegate = null;
        for (AgentConnection delegate : tmp) {
            if (!delegate.isOnline())
                continue;
            int capacity = delegate.getAvailableCPU();
            if (capacity > bestCapacity) {
                bestCapacity = capacity;
                bestDelegate = delegate;
            }
        }
        if (bestCapacity > 0 && bestDelegate != null) {
            File file = files.get(job.getJobArchiveRef());
            tmp.remove(bestDelegate);
            String jobManifest = getJobManifest(file);
            if (jobManifest == null) {
                Log.error("[" + job.getName() + "] Couldn't schedule, job has not manifest.json.");
                return null;
            }
            RemoteJob scheduleJob = bestDelegate.scheduleJobCallback(
                    job.getName() + String.format("_bal%06d", (int) (Math.random() * 1000000)), job.getJobArchiveRef(),
                    jobManifest, myUrl);
            Log.debug("[" + job.getName() + "] " + (scheduleJob == null ? "is null" : "is not null"));
            bestDelegate.updateAvailableCPU(0);
            return scheduleJob;
        }
        return null;
    }

    private String getJobManifest(File zipFile) throws IOException {
        return ZipUtils.getFileAsString(zipFile, "manifest.json");
    }

    private void resolveAgents() {
        for (String url : delegateUrls) {
            if (hasDelegate(url))
                continue;
            Log.debug("trying delegate at " + url);
            try (InputStream is = http.openUrlStream(new URL(new URL(url), "/status"))) {
                JsonObject jsonObject = JsonParser.parseReader(new InputStreamReader(is)).getAsJsonObject();
                jsonObject.get("availableCPU").getAsInt();
                Log.info("adding delegate at " + url);
                AgentConnection agent = new AgentConnection(url, true, executor, http);
                agent.scheduleStatusCheck();
                synchronized (delegates) {
                    delegates.add(agent);
                }
            } catch (Exception e) {
            }
        }
    }

    private boolean hasDelegate(String url) {
        for (AgentConnection agentConnection : Util.safeCopy(delegates)) {
            if (url.equals(agentConnection.getUrl()))
                return true;
        }
        return false;
    }

    private void updateJobs() throws IOException {
        for (BaseJob baseJob : Util.safeCopy(jobs)) {
            BalancingJob bj = (BalancingJob) baseJob;
            if (!bj.hasDelegate())
                continue;
            if (bj.getStatus() != BaseJob.Status.DONE) {
                RemoteJob delegate = bj.getDelegate();
                if (delegate.getStatus() == BaseJob.Status.DONE) {
                    jobDone(bj);
                } else {
                    AgentConnection agent = delegate.getAgent();
                    if (!agent.isOnline() && agent.getOfflineCounter() > 60) {
                        Log.warn("[" + bj.getName() + "] Agent " + agent.getUrl() + " is offline for at least "
                                + agent.getOfflineCounter() + "s, rescheduling.");
                        bj.updateStatus(BaseJob.Status.NEW);
                        bj.eraseDelegate();
                    } else {
                        bj.updateStatus(delegate.getStatus());
                    }
                }
            }
        }
    }

    private void jobDone(final BalancingJob bj) throws IOException {
        if (bj.isDownloading())
            return;
        bj.setDownloading(true);
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    File f = bj.getDelegate().getResultArchive(http);
                    String resultArchiveRef = files.addAsFile(f);
                    bj.updateResultArchiveRef(resultArchiveRef);
                    bj.updateStatus(BaseJob.Status.DONE);
                    Log.info("[" + bj.getName() + "] Deleting file '" + bj.getJobArchiveRef() + "'.");
                    files.delete(bj.getJobArchiveRef());
                } catch (Exception e) {
                    Log.error("[" + bj.getName() + "] couldn't update the job status to DONE.");
                    e.printStackTrace(System.out);
                }
                bj.setDownloading(false);
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
        return new NewJobHandler(this, jobs, files);
    }

    @Override
    protected Handler getStatusPage() {
        return new BalancingStatusPage(jobs, delegates, http, files, startTime);
    }

    private void startBalancingTasks() {
        executor.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                resolveAgents();
            }
        }, 0, 1, TimeUnit.SECONDS);

        executor.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                try {
                    doBalancing();
                } catch (Exception e) {
                    Log.error("Problem balancing");
                    e.printStackTrace(System.out);
                }
            }
        }, 100, 1000, TimeUnit.MILLISECONDS);

        executor.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                try {
                    updateJobs();
                } catch (Exception e) {
                    Log.error("Problem balancing");
                    e.printStackTrace(System.out);
                }
            }
        }, 100, 100, TimeUnit.MILLISECONDS);
    }

    @Override
    public BaseJob newJob(String name, String jobArchiveRef) {
        return new BalancingJob(name, jobArchiveRef, http);
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            System.out.println("Syntax: balancingagent <port> <agent base folder> <delegate list> <my url>");
            return;
        }
        int port = Integer.parseInt(args[0]);
        File delegateList = new File(args[2]);
        BalancingAgent agent = new BalancingAgent(new File(args[1]), FileUtils.readLines(delegateList), args[3]);
        agent.startBalancingTasks();
        agent.startAgent(port);
    }

    @Override
    public BaseJob newJob(String name, String remoteJobArchiveRef, String remoteUrl, int cpuReq) {
        return null;
    }

    @Override
    protected Handler getLogHandler() {
        return new LogHandler(jobs);
    }
}
