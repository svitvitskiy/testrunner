package testrunner;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.google.api.services.compute.model.Instance;

public class GCloudManager implements Runnable {

    private List<BaseJob> jobs;
    private ScheduledExecutorService executor;
    private List<Worker> workers;
    private List<AgentConnection> delegates;
    private GCloudUtil gcloudUtil;

    private class Worker {
        private Instance instance;
        private boolean running;

        public Worker(Instance instance) {
            Log.debug("Worker: " + instance.getName() + "," + GCloudUtil.getInstanceIp(instance));
            this.instance = instance;
        }

        public Instance getInstance() {
            return instance;
        }

        public boolean isRunning() {
            return running;
        }

        public void start() throws IOException {
            running = gcloudUtil.start(instance);
        }

        public void stop() throws IOException {
            running = !gcloudUtil.stop(instance);

        }
    }

    public GCloudManager(List<BaseJob> jobs, List<AgentConnection> delegates, List<Instance> instances, String myIp)
            throws IOException, GeneralSecurityException {
        this.jobs = jobs;
        this.delegates = delegates;
        this.workers = new ArrayList<Worker>();
        this.gcloudUtil = new GCloudUtil();
        for (Instance instance : instances) {
            workers.add(new Worker(instance));
        }
        this.executor = Executors.newScheduledThreadPool(1);
        executor.scheduleAtFixedRate(this, 1, 5, TimeUnit.SECONDS);
    }

    public void run() {
        try {
            int count = getUnscheduledCount();
            int slots = getAvailableSlots();

            if (slots < count) {
                Log.info("Scheduled (" + count + ") > available slots (" + slots + ") starting a worker.");
                try {
                    startWorker();
                } catch (IOException e) {
                    Log.error("Couldn't start the worker.");
                    Log.error(e);
                }
            } else if (count == 0) {
                try {
                    stopWorker();
                } catch (IOException e) {
                    Log.error("Couldn't stop the worker.");
                    Log.error(e);
                }
            }
        } catch (Exception e) {
            Log.error("Abnormal state");
            Log.error(e);
        }
    }

    private void startWorker() throws IOException {
        boolean found = false;
        for (Worker worker : workers) {
            if (!worker.isRunning()) {
                worker.start();
                if (worker.isRunning()) {
                    Log.info("Started worker " + worker.getInstance().getName());
                    found = true;
                }
                break;
            }
        }
        if (!found) {
            Log.info("No more available workers to start");
        }
    }

    private void stopWorker() throws IOException {
        for (Worker worker : workers) {
            if (worker.isRunning()) {
                String agentUrl = getAgentUrl(GCloudUtil.getInstanceIp(worker.getInstance()));
                AgentConnection agent = findAgent(agentUrl);
                if (agent != null && totalRunningJobs(agent) == 0) {
                    Log.info("Attempting to stop " + worker.getInstance().getName() + "(" + agentUrl + ").");
                    worker.stop();
                    if (!worker.isRunning()) {
                        Log.info("Stopped worker " + worker.getInstance().getName());
                    }
                    break;
                }
            }
        }
    }

    private int totalRunningJobs(AgentConnection agent) {
        int count = 0;
        for (BaseJob baseJob : Util.safeCopy(jobs)) {
            BalancingJob bj = (BalancingJob) baseJob;
            RemoteJob remoteJob = bj.getDelegate();
            if (remoteJob != null && remoteJob.getAgent() == agent
                    && bj.getStatus() != BaseJob.Status.DONE && bj.getStatus() != BaseJob.Status.ERROR) {
                count++;
            }
        }
        return count;
    }

    private AgentConnection findAgent(String url) {
        List<AgentConnection> tmp = Util.safeCopy(delegates);
        for (AgentConnection con : tmp) {
            if (url.equals(con.getUrl()))
                return con;
        }
        return null;
    }

    private int getAvailableSlots() {
        int slots = 0;
        List<AgentConnection> tmp = Util.safeCopy(delegates);
        for (AgentConnection agentConnection : tmp) {
            if (agentConnection.isServing()) {
                slots += agentConnection.getAvailableCPU() / 3;
            }
        }
        return slots;
    }

    private int getUnscheduledCount() {
        int count = 0;
        for (BaseJob baseJob : Util.safeCopy(jobs)) {
            BalancingJob bj = (BalancingJob) baseJob;
            if (bj.getDelegate() == null && bj.getStatus() != BaseJob.Status.ERROR) {
                count++;
            }
        }
        return count;
    }

    public static String getAgentUrl(String ip) {
        return "http://" + ip + ":8000/";
    }
}
