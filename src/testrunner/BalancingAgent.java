package testrunner;

import java.awt.Component.BaselineResizeBehavior;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.apache.commons.io.FileUtils;

import com.google.api.services.compute.model.Instance;
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
    private GCloudManager gcloudManager;

    public BalancingAgent(File agentBase, List<String> delegateUrls, String myUrl) {
        this.myUrl = myUrl;
        int nThreads = Runtime.getRuntime().availableProcessors();
        this.executor = Executors.newScheduledThreadPool(Math.min(64, nThreads * 8));
        files = new FileStore(new File(agentBase, "store"));
        this.delegateUrls = delegateUrls;
        this.http = new HttpIface(1000 /* connectionTimeout */, 20000 /* socketTimeout */);
        this.startTime = System.currentTimeMillis();
    }
    
    public static BalancingAgent createGCloud(File agentBase) throws IOException, GeneralSecurityException {
        List<String> delegates = new ArrayList<String>();
        String myIp = GCloudUtil.getGCloudMetaVal(GCloudUtil.META_INSTANCE_IP);
        List<Instance> instances = new GCloudUtil().getAllGCloudInstances();

        for (Instance instance : instances) {
            String instanceIp = GCloudUtil.getInstanceIp(instance);
            if (instanceIp != null && !myIp.equals(instanceIp)) {
                String delegateUrl = GCloudManager.getAgentUrl(instanceIp);
                System.out.println("Using delegate: " + delegateUrl);
                delegates.add(delegateUrl);
            }
        }
                
        BalancingAgent ba = new BalancingAgent(agentBase, delegates, GCloudManager.getAgentUrl(myIp));
        ba.gcloudManager = new GCloudManager(ba.jobs, ba.delegates, instances, myIp);
        return ba;
    }

    private void doBalancing() throws IOException {
        List<AgentConnection> delegatesCopy = Util.safeCopy(delegates);
        List<BaseJob> jobsCopy = Util.safeCopy(jobs);
        Log.debug("Trying to balance " + jobsCopy.size() + " jobs.");

        // Finding the job of the highest priority
        List<BaseJob> unsched = jobsCopy.stream().filter(job -> !((BalancingJob) job).hasDelegate() && job.getStatus() == BaseJob.Status.NEW)
                .collect(Collectors.toList());
        Collections.sort(unsched, (BaseJob o1, BaseJob o2) -> Integer.compare(o1.getPriority(), o2.getPriority()));

        int i = 0;
        for (BaseJob baseJob : unsched) {
            BalancingJob bj = (BalancingJob) baseJob;
            RemoteJob remoteJob = tryDelegate(bj, delegatesCopy);
            if (remoteJob != null) {
                bj.updateDelegate(remoteJob);
                Log.info("[" + bj.getName() + "@" + remoteJob.getPriority() + "] Scheduled job with remote agent '"
                        + remoteJob.getAgent().getUrl() + "'.");
            }
            if (i++ >= 9)
                break;
        }
    }

    private RemoteJob tryDelegate(BalancingJob job, List<AgentConnection> tmp) throws IOException {
        Log.debug("[" + job.getName() + "] Trying to balance");
        int bestCapacity = 0;
        AgentConnection bestDelegate = null;
        for (AgentConnection delegate : tmp) {
            if (!delegate.isServing())
                continue;
            int capacity = delegate.getAvailableCPU();
            if (capacity > bestCapacity) {
                bestCapacity = capacity;
                bestDelegate = delegate;
            }
        }
        if (bestCapacity > 0 && bestDelegate != null) {
            tmp.remove(bestDelegate);
            String jobManifest = job.getJobManifest(files);
            if (jobManifest == null) {
                job.updateStatus(BaseJob.Status.ERROR);
                Log.error("[" + job.getName() + "] Couldn't schedule, job has not manifest.json.");
                return null;
            }
            RemoteJob scheduleJob = bestDelegate.scheduleJobCallback(
                    job.getName() + String.format("_bal%06d", (int) (Math.random() * 1000000)), job.getJobArchiveRef(),
                    job.getPriority(), jobManifest, myUrl);
            Log.debug("[" + job.getName() + "@" + job.getPriority() + "] "
                    + (scheduleJob == null ? "is null" : "is not null"));
            bestDelegate.updateAvailableCPU(0);
            return scheduleJob;
        }
        return null;
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
                AgentConnection agent = new AgentConnection(url, false, executor, http);
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
            if (bj.getStatus() != BaseJob.Status.DONE && bj.getStatus() != BaseJob.Status.ERROR) {
                RemoteJob delegate = bj.getDelegate();
                if (delegate.isMissing()) {
                    AgentConnection agent = delegate.getAgent();
                    Log.warn("[" + bj.getName() + "] job is missing on remote agent (" + agent.getUrl()
                            + ")after a reasonable timeout, rescheduling.");
                    rescheduleJob(bj);
                } else if (delegate.getStatus() == BaseJob.Status.DONE) {
                    jobDone(bj);
                } else {
                    AgentConnection agent = delegate.getAgent();
                    if (!agent.isOnline() && agent.getOfflineCounter() > 60) {
                        Log.warn("[" + bj.getName() + "@" + bj.getPriority() + "] Agent " + agent.getUrl()
                                + " is offline for at least " + agent.getOfflineCounter() + "s, rescheduling.");
                        rescheduleJob(bj);
                    } else {
                        bj.updateStatus(delegate.getStatus());
                    }
                }
            }
        }
    }

    private void rescheduleJob(BalancingJob bj) {
        bj.updateStatus(BaseJob.Status.NEW);
        bj.eraseDelegate();
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
                    Log.info("[" + bj.getName() + "@" + bj.getPriority() + "] Deleting file '" + bj.getJobArchiveRef()
                            + "'.");
                    files.delete(bj.getJobArchiveRef());
                } catch (Exception e) {
                    Log.error(
                            "[" + bj.getName() + "@" + bj.getPriority() + "] couldn't update the job status to DONE.");
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
    public BaseJob newJob(String name, String jobArchiveRef, int priority) {
        return new BalancingJob(name, jobArchiveRef, priority, http);
    }

    public static void main(String[] args) throws Exception {
        List<String> a = new ArrayList<String>(Arrays.asList(args));
        boolean isGcloud = CmdUtils.hasKen(a, "-gcloud");
        if (a.size() < 2) {
            printHelp();
            return;
        }
        int port = Integer.parseInt(a.get(0));
        File agentBase = new File(a.get(1));

        BalancingAgent agent;
        if (isGcloud) {
            Log.info("Starting balancing agent with GCloud on port " + port);
            agent = createGCloud(agentBase); 
        } else {
            if (a.size() < 4) {
                printHelp();
                return;
            }
            List<String> delegates = FileUtils.readLines(new File(a.get(2)));
            agent = new BalancingAgent(agentBase, delegates, a.get(3));
        }
        
        agent.startBalancingTasks();
        agent.startAgent(port);
    }

    private static void printHelp() {
        System.out.println("Syntax: balancingagent [-gcloud] <port> <agent base folder> [delegate_list] [my_url]");
        System.out.println("  Where:");
        System.out.println("          gcloud    Resolve delegate_list and my_url from GCloud, the appropriate"
                + " arguments will be ignored.");
    }

    @Override
    public BaseJob newJob(String name, String remoteJobArchiveRef, String remoteUrl, int cpuReq, int priority) {
        return null;
    }

    @Override
    protected Handler getLogHandler() {
        return new LogHandler(jobs);
    }
}
