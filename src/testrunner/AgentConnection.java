package testrunner;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
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

    public AgentConnection(String url, ScheduledExecutorService executor) {
        this.url = url;
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
                    online = false;
                    System.out.println("ERROR: updating job status for an agent '" + url + "'");
                    e.printStackTrace();
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
        try (InputStream is = url2.openStream()) {
            online = true;
            JsonObject jsonObject = JsonParser.parseReader(new InputStreamReader(is)).getAsJsonObject();
            availableCPU = jsonObject.get("availableCPU").getAsInt();
            JsonElement jsonElement = jsonObject.get("jobs");
            int tmp0 = 0, tmp1 = 0;
            for (JsonElement jsonElement2 : jsonElement.getAsJsonArray()) {
                JsonObject asJsonObject = jsonElement2.getAsJsonObject();
                updateJob(asJsonObject);
                tmp0 += 1;
                tmp1 += "DONE".equals(asJsonObject.get("status").getAsString()) ? 0 : 1;
            }
            totalJobs = tmp0;
            totalRunningJobs = tmp1;
        }
    }

    private void updateJob(JsonObject job) {
        String jobName = job.get("name").getAsString();
        for (RemoteJob remoteJob : BaseAgent.safeCopy(jobs)) {
            if (jobName.equals(remoteJob.getName())) {
                remoteJob.updateStatus(job.get("status").getAsString());
                remoteJob.updateResultArchiveRef(job.get("resultArchiveRef").getAsString());
                break;
            }
        }
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
        System.out.println("INFO: [" + name + "] file id:" + fileid);
        RemoteJob job = scheduleJob(name, fileid);

        if (job != null) {
            synchronized (jobs) {
                jobs.add(job);
            }
        }

        return job;
    }

    private RemoteJob scheduleJob(String name, String fileid) throws IOException {
        HttpClient httpclient = new DefaultHttpClient();
        HttpPost httpPost = new HttpPost(new URL(new URL(url), "/new").toExternalForm());

        String json = "{\"jobName\":\"" + name + "\",\"jobArchiveRef\":\"" + fileid + "\"}";
        return doScheduleJobArchive(name, httpclient, httpPost, json);
    }

    private RemoteJob doScheduleJobArchive(String name, HttpClient httpclient, HttpPost httpPost, String json)
            throws UnsupportedEncodingException, IOException, ClientProtocolException {
        StringEntity entity = new StringEntity(json);
        httpPost.setEntity(entity);
        httpPost.setHeader("Accept", "application/json");
        httpPost.setHeader("Content-type", "application/json");
        HttpResponse response = httpclient.execute(httpPost);

        if (response.getStatusLine().getStatusCode() != 200) {
            String responseBody = EntityUtils.toString(response.getEntity());
            JsonObject jsonObject = JsonParser.parseString(responseBody).getAsJsonObject();
            System.out.println("ERROR: [" + name + "] " + jsonObject.get("message").getAsString());
            return null;
        }
        return new RemoteJob(name, url);
    }

    public RemoteJob scheduleJobCallback(String name, String jobArchiveRef, String manifest, String myUrl)
            throws IOException {
        HttpClient httpclient = new DefaultHttpClient();
        HttpPost httpPost = new HttpPost(new URL(new URL(url), "/new").toExternalForm());

        String json = "{" + "\"jobName\":\"" + name + "\"," + "\"remoteJobArchiveRef\":\"" + jobArchiveRef + "\","
                + "\"remoteUrl\":\"" + myUrl + "\"," + "\"manifest\":" + manifest + "}";
        RemoteJob job = doScheduleJobArchive(name, httpclient, httpPost, json);

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

        AgentConnection agent = new AgentConnection(agentUrl, executor2);
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
}
