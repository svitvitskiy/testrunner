package testrunner;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.apache.http.HttpResponse;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.conn.HttpHostConnectException;
import org.apache.http.util.EntityUtils;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import testrunner.BaseJob.Status;

public class AgentConnection {
    private static final long GOOD_UPTIME_MS = 30000;
    private String url;
    private boolean online;
    private ScheduledExecutorService executor;
    private int availableCPU;
    private List<RemoteJob> jobs;
    private int totalJobs;
    private int totalRunningJobs;
    private ScheduledFuture<?> future;
    private List<Event> events = new ArrayList<Event>();
    private int offlineCounter;
    private boolean autoRetry;
    private HttpIface http;

    public static enum EventType {
        UP, DOWN
    }

    public static class Event {
        private EventType type;
        private long time;

        public Event(EventType type, long time) {
            this.type = type;
            this.time = time;
        }

        public EventType getType() {
            return type;
        }

        public long getTime() {
            return time;
        }
    }

    public AgentConnection(String url, boolean autoRetry, ScheduledExecutorService executor, HttpIface http) {
        this.url = url;
        this.autoRetry = autoRetry;
        this.executor = executor;
        this.http = http;
        this.jobs = new ArrayList<RemoteJob>();
    }

    public void scheduleStatusCheck() {
        future = executor.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                try {
                    updateJobStatus();
                } catch (Exception e) {
                    if (online == true) {
                        connectiondDown();
                        if (!(e instanceof HttpHostConnectException)) {
                            Log.error("updating job status for an agent '" + url + "'");
                        }
                    }
                    online = false;
                    ++offlineCounter;
                    if (!(e instanceof HttpHostConnectException)) {
                        e.printStackTrace(System.out);
                    }
                }
            }
        }, 0, 1, TimeUnit.SECONDS);
    }

    public void shutdown() {
        if (future != null)
            future.cancel(false);
    }

    private void updateJobStatus() throws MalformedURLException, IOException {
        boolean updateNecessary = false;
        for (RemoteJob remoteJob : Util.safeCopy(jobs)) {
            Status status = remoteJob.getStatus();
            if (status != BaseJob.Status.DONE && status != BaseJob.Status.ERROR) {
                updateNecessary = true;
                break;
            }
        }

        URL url2 = new URL(new URL(url), "/status");
        Set<String> updated = new HashSet<String>();

        try (InputStream is = http.openUrlStream(url2)) {
            if (online == false) {
                connectionUp();
            }
            online = true;
            JsonObject jsonObject = JsonParser.parseReader(new InputStreamReader(is)).getAsJsonObject();
            availableCPU = jsonObject.get("availableCPU").getAsInt();
            JsonElement jsonElement = jsonObject.get("jobs");
            int newTotalJobs = 0, newTotalRunningJobs = 0;
            for (JsonElement jsonElement2 : jsonElement.getAsJsonArray()) {
                JsonObject asJsonObject = jsonElement2.getAsJsonObject();
                String jobName = asJsonObject.get("name").getAsString();
                if (updateJob(asJsonObject)) {
                    updated.add(jobName);
                }

                newTotalJobs += 1;
                newTotalRunningJobs += "DONE".equals(asJsonObject.get("status").getAsString()) ? 0 : 1;
            }

            for (RemoteJob remoteJob : Util.safeCopy(jobs)) {
                if (remoteJob.getStatus() != BaseJob.Status.DONE && !remoteJob.isMissing()
                        && !updated.contains(remoteJob.getName())) {
                    remoteJob.incrementRetryCounter();
                    if (remoteJob.getRetryCounter() > 60) {
                        if (autoRetry) {
                            Log.warn("[" + remoteJob.getName() + "] Not found on remote agent, rescheduling.");
                            rescheduleJob(remoteJob);
                        } else {
                            remoteJob.setMissing(true);
                        }
                        remoteJob.resetRetryCounter();
                    }
                }
            }

            totalJobs = newTotalJobs;
            totalRunningJobs = newTotalRunningJobs;
        } catch (IOException e) {
            if (updateNecessary)
                throw e;
        }
    }

    private void connectionUp() {
        offlineCounter = 0;
        events.add(new Event(EventType.UP, System.currentTimeMillis()));
        System.out.println((char) 27 + "[92m+ " + url + " UP" + (char) 27 + "[0m");
    }

    private void connectiondDown() {
        offlineCounter = 0;
        events.add(new Event(EventType.DOWN, System.currentTimeMillis()));
        System.out.println((char) 27 + "[95m+ " + url + " DOWN" + (char) 27 + "[0m");
    }

    private boolean updateJob(JsonObject job) {
        String jobName = job.get("name").getAsString();
        for (RemoteJob remoteJob : Util.safeCopy(jobs)) {
            if (jobName.equals(remoteJob.getName())) {
                remoteJob.updateStatus(job.get("status").getAsString());
                remoteJob.updateResultArchiveRef(job.get("resultArchiveRef").getAsString());
                remoteJob.resetRetryCounter();
                return true;
            }
        }
        return false;
    }

    public int getAvailableCPU() {
        return availableCPU;
    }

    public int getTotalJobs() {
        return totalJobs;
    }

    public int getTotalRunningJobs() {
        return totalRunningJobs;
    }

    public String getUrl() {
        return url;
    }

    public boolean isOnline() {
        return online;
    }

    void updateAvailableCPU(int availableCPU) {
        this.availableCPU = availableCPU;
    }

    public RemoteJob scheduleJob(String name, File jobArchive, int priority) throws IOException {
        if (!online)
            return RemoteJob.WAIT;
        String fileid = uploadJobArchive(jobArchive);
        Log.info("[" + name + "] file id:" + fileid);
        if (!scheduleJob(name, fileid, priority))
            return null;

        RemoteJob job = new RemoteJob(name, priority, this);
        job.updateJobArchive(jobArchive);

        if (job != null) {
            synchronized (jobs) {
                jobs.add(job);
            }
        }

        return job;
    }

    public RemoteJob scheduleJobCallback(String name, String jobArchiveRef, int priority, String manifest, String myUrl)
            throws IOException {
        if (!online)
            return null;
        String json = "{" + "\"jobName\":\"" + name + "\"," + "\"remoteJobArchiveRef\":\"" + jobArchiveRef + "\","
                + "\"priority\":\"" + priority + "\"," + "\"remoteUrl\":\"" + myUrl + "\"," + "\"manifest\":" + manifest
                + "}";
        if (!doScheduleJobArchive(name, json))
            return null;
        RemoteJob job = new RemoteJob(name, priority, this);
        job.updateJobArchiveRef(jobArchiveRef);
        job.updateManifest(manifest);
        job.updateRemoteUrl(myUrl);

        if (job != null) {
            synchronized (jobs) {
                jobs.add(job);
            }
        }

        return job;
    }

    private void rescheduleJob(RemoteJob remoteJob) throws IOException {
        remoteJob.setStatus(BaseJob.Status.NEW);
        String fileid = uploadJobArchive(remoteJob.getJobArchive());
        Log.info("[" + remoteJob.getName() + "] file id:" + fileid);
        if (!scheduleJob(remoteJob.getName(), fileid, remoteJob.getPriority())) {
            Log.warn("[" + remoteJob.getName() + "] Couldn't reschedule a job.");
        }
    }

    private boolean scheduleJob(String name, String fileid, int priority) throws IOException {
        String json = "{\"jobName\":\"" + name + "\"," + "\"jobArchiveRef\":\"" + fileid + "\"," + "\"priority\":\""
                + priority + "\"" + "}";
        return doScheduleJobArchive(name, json);
    }

    private boolean doScheduleJobArchive(String name, String json)
            throws UnsupportedEncodingException, IOException, ClientProtocolException {
        URL newUrl = new URL(new URL(url), "/new");
        Log.debug("[" + name + "] scheduling job with '" + newUrl.toExternalForm() + "'");
        HttpResponse response = http.postString(newUrl, json);

        String responseBody = EntityUtils.toString(response.getEntity());
        if (response.getStatusLine().getStatusCode() != 200) {
            JsonObject jsonObject = JsonParser.parseString(responseBody).getAsJsonObject();
            Log.error("[" + name + "] " + jsonObject.get("message").getAsString());
            return false;
        }
        return true;
    }

    private String uploadJobArchive(File jobArchive) throws IOException {
        HttpResponse response = http.upload(new URL(new URL(url), "/upload"), jobArchive, "file");
        String responseBody = EntityUtils.toString(response.getEntity());
        JsonObject jsonObject = JsonParser.parseString(responseBody).getAsJsonObject();
        if (response.getStatusLine().getStatusCode() == 200) {
            return jsonObject.get("id").getAsString();
        } else {
            System.out.println(jsonObject.get("message").getAsString());
        }
        return null;
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        if (args.length < 3) {
            System.out.println("Syntax: agentconnection <agent url> <job zip> <output zip>");
            return;
        }
        ScheduledExecutorService executor2 = Executors.newScheduledThreadPool(1);
        String agentUrl = args[0];
        File jobArchive = new File(args[1]);

        HttpIface http2 = new HttpIface(1000 /* connectionTimeout */, 20000 /* socketTimeout */);
        AgentConnection agent = new AgentConnection(agentUrl, true, executor2, http2);
        agent.scheduleStatusCheck();
        RemoteJob job;
        do {
            job = agent.scheduleJob(jobArchive.getName().replaceAll("\\.zip$", ""), jobArchive, 255);
            if (job == null) {
                System.out.println("Couldn't schedule a job");
                return;
            }
            if (job == RemoteJob.WAIT) {
                Thread.sleep(1000);
            }
        } while (job == RemoteJob.WAIT);
        job.waitDone();
        System.out.println("[" + job.getName() + "] Job finished: " + job.getStatus());
        System.out.println("[" + job.getName() + "] Result archive: " + job.getResultArchiveRef());
        executor2.shutdown();
        job.getResultArchive(http2).renameTo(new File(args[2]));
    }

    public List<Event> getEvents() {
        return Util.safeCopy(events);
    }

    public int getOfflineCounter() {
        return offlineCounter;
    }

    public boolean isServing() {
        if (!online)
            return false;

        int idx = events.size() - 1;
        if (idx >= 0 && events.get(idx).getType() == EventType.UP) {
            if (System.currentTimeMillis() - events.get(idx).getTime() > GOOD_UPTIME_MS)
                return true;
        }
        --idx;
        if (idx >= 0 && events.get(idx).getType() == EventType.DOWN) {
            --idx;
            if (idx >= 0 && events.get(idx).getType() == EventType.UP) {
                long prevUptime = events.get(idx + 1).getTime() - events.get(idx).getTime();
                if (prevUptime < GOOD_UPTIME_MS)
                    return false;
            }
        }

        return true;
    }
}
