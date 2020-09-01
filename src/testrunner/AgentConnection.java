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
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.entity.mime.MultipartEntity;
import org.apache.http.entity.mime.content.FileBody;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.util.EntityUtils;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class AgentConnection {
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

    public AgentConnection(String url, boolean autoRetry, ScheduledExecutorService executor) {
        this.url = url;
        this.autoRetry = autoRetry;
        this.executor = executor;
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
                        Log.error("updating job status for an agent '" + url + "'");
                    }
                    online = false;
                    ++offlineCounter;
                    e.printStackTrace(System.out);
                }
            }
        }, 0, 1, TimeUnit.SECONDS);
    }

    public void shutdown() {
        if (future != null)
            future.cancel(false);
    }

    private void updateJobStatus() throws MalformedURLException, IOException {
        URL url2 = new URL(new URL(url), "/status");
        Set<String> updated = new HashSet<String>();
        try (InputStream is = Util.openUrlStream(url2, 1000, 1000)) {
            if (online == false) {
                connectionUp();
            }
            online = true;
            JsonObject jsonObject = JsonParser.parseReader(new InputStreamReader(is)).getAsJsonObject();
            availableCPU = jsonObject.get("availableCPU").getAsInt();
            JsonElement jsonElement = jsonObject.get("jobs");
            int tmp0 = 0, tmp1 = 0;
            for (JsonElement jsonElement2 : jsonElement.getAsJsonArray()) {
                JsonObject asJsonObject = jsonElement2.getAsJsonObject();
                String jobName = asJsonObject.get("name").getAsString();
                if (updateJob(asJsonObject)) {
                    updated.add(jobName);
                }

                tmp0 += 1;
                tmp1 += "DONE".equals(asJsonObject.get("status").getAsString()) ? 0 : 1;
            }
            totalJobs = tmp0;
            totalRunningJobs = tmp1;
        }
        if (autoRetry) {
            for (RemoteJob remoteJob : Util.safeCopy(jobs)) {
                if (remoteJob.getStatus() != BaseJob.Status.DONE && !updated.contains(remoteJob.getName())) {
                    remoteJob.incrementRetryCounter();
                    if (remoteJob.getRetryCounter() > 60) {
                        remoteJob.resetRetryCounter();
                        Log.warn("[" + remoteJob.getName() + "] Not found on remote agent, rescheduling.");
                        rescheduleJob(remoteJob);
                    }
                }
            }
        }
    }

    private void connectionUp() {
        offlineCounter = 0;
        events.add(new Event(EventType.UP, System.currentTimeMillis()));
    }

    private void connectiondDown() {
        offlineCounter = 0;
        events.add(new Event(EventType.DOWN, System.currentTimeMillis()));
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

    public RemoteJob scheduleJob(String name, File jobArchive) throws IOException {
        String fileid = uploadJobArchive(jobArchive);
        Log.info("[" + name + "] file id:" + fileid);
        if (!scheduleJob(name, fileid))
            return null;

        RemoteJob job = new RemoteJob(name, this);
        job.updateJobArchive(jobArchive);

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
        if (!scheduleJob(remoteJob.getName(), fileid)) {
            Log.warn("[" + remoteJob.getName() + "] Couldn't reschedule a job.");
        }
    }

    private boolean scheduleJob(String name, String fileid) throws IOException {
        HttpClient httpclient = new DefaultHttpClient();
        HttpPost httpPost = new HttpPost(new URL(new URL(url), "/new").toExternalForm());

        String json = "{\"jobName\":\"" + name + "\",\"jobArchiveRef\":\"" + fileid + "\"}";
        return doScheduleJobArchive(name, httpclient, httpPost, json);
    }

    private boolean doScheduleJobArchive(String name, HttpClient httpclient, HttpPost httpPost, String json)
            throws UnsupportedEncodingException, IOException, ClientProtocolException {
        StringEntity entity = new StringEntity(json);
        httpPost.setEntity(entity);
        httpPost.setHeader("Accept", "application/json");
        httpPost.setHeader("Content-type", "application/json");
        HttpResponse response = httpclient.execute(httpPost);

        if (response.getStatusLine().getStatusCode() != 200) {
            String responseBody = EntityUtils.toString(response.getEntity());
            JsonObject jsonObject = JsonParser.parseString(responseBody).getAsJsonObject();
            Log.error("[" + name + "] " + jsonObject.get("message").getAsString());
            return false;
        }
        return true;
    }

    public RemoteJob scheduleJobCallback(String name, String jobArchiveRef, String manifest, String myUrl)
            throws IOException {
        HttpClient httpclient = new DefaultHttpClient();
        String newUrl = new URL(new URL(url), "/new").toExternalForm();
        HttpPost httpPost = new HttpPost(newUrl);

        Log.debug("[" + name + "] scheduling job with '" + newUrl + "'");

        String json = "{" + "\"jobName\":\"" + name + "\"," + "\"remoteJobArchiveRef\":\"" + jobArchiveRef + "\","
                + "\"remoteUrl\":\"" + myUrl + "\"," + "\"manifest\":" + manifest + "}";
        if (!doScheduleJobArchive(name, httpclient, httpPost, json))
            return null;
        RemoteJob job = new RemoteJob(name, this);
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

    private String uploadJobArchive(File jobArchive) throws IOException {
        HttpClient httpclient = new DefaultHttpClient();
        HttpPost httpPost = new HttpPost(new URL(new URL(url), "/upload").toExternalForm());

        FileBody uploadFilePart = new FileBody(jobArchive);
        MultipartEntity reqEntity = new MultipartEntity();
        reqEntity.addPart("file", uploadFilePart);
        httpPost.setEntity(reqEntity);

        HttpResponse response = httpclient.execute(httpPost);

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

        AgentConnection agent = new AgentConnection(agentUrl, true, executor2);
        agent.scheduleStatusCheck();
        RemoteJob job = agent.scheduleJob(jobArchive.getName().replaceAll("\\.zip$", ""), jobArchive);
        if (job == null) {
            System.out.println("Couldn't schedule a job");
            return;
        }
        job.waitDone();
        System.out.println("[" + job.getName() + "] Job finished: " + job.getStatus());
        System.out.println("[" + job.getName() + "] Result archive: " + job.getResultArchiveRef());
        executor2.shutdown();
        job.getResultArchive().renameTo(new File(args[2]));
    }

    public List<Event> getEvents() {
        return Util.safeCopy(events);
    }

    public int getOfflineCounter() {
        return offlineCounter;
    }
}
