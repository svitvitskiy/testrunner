package testrunner;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.util.List;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Create a new job with this agent. A job archive needs to be uploaded prior to
 * this call using '/upload' endpoint. Example of the request JSON: {
 * "jobArchiveRef": "xyxasda223akdad" }
 * 
 * where the value "xyxasda223akdad" is whatever unique resource identifier the
 * '/upload' endpoint has returned previously.
 * 
 * @author vitvitskyy
 */
public class NewJobHandler implements BaseAgent.Handler {
    private List<BaseJob> jobs;
    private BaseJob.JobFactory factory;
    private FileStore files;

    public NewJobHandler(BaseJob.JobFactory factory, List<BaseJob> jobs, FileStore files) {
        this.jobs = jobs;
        this.files = files;
        this.factory = factory;
    }

    public void handle(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if ("POST".equals(request.getMethod())) {
            JsonObject jsonObject = JsonParser.parseReader(new InputStreamReader(request.getInputStream()))
                    .getAsJsonObject();
            boolean success;
            String message = null;
            try {
                success = addJob(jsonObject);
            } catch (Exception e) {
                success = false;
                message = e.getMessage();
            }
            response.setStatus(success ? 200 : 500);
            PrintStream ps = new PrintStream(response.getOutputStream());
            ps.println("{");
            ps.println("\"success\":" + success);
            if (message != null) {
                ps.println(", \"message\":\"" + message + "\"");
            }
            ps.println("}");
        }
    }

    private boolean addJob(JsonObject jsonObject) throws Exception {
        JsonElement jsonElement0 = jsonObject.get("jobName");
        JsonElement jsonElement1 = jsonObject.get("jobArchiveRef");
        JsonElement jsonElement2 = jsonObject.get("remoteJobArchiveRef");
        if (jsonElement0 == null)
            throw new IllegalArgumentException("Invalid job request, 'jobName' attribute is missing.");

        String jobName = jsonElement0.getAsString();
        if (nameClash(jobName))
            throw new IllegalArgumentException("Duplicate job, job with name '" + jobName + "' exists.");
        BaseJob newJob;
        if (jsonElement1 != null) {
            String jobArchiveRef = jsonElement1.getAsString();
            if (!files.has(jobArchiveRef))
                return false;
            newJob = factory.newJob(jobName, jobArchiveRef);
        } else if (jsonElement2 != null) {
            JsonElement jsonElement3 = jsonObject.get("remoteUrl");
            JsonElement jsonElement4 = jsonObject.get("manifest");
            if (jsonElement3 == null || jsonElement4 == null) {
                throw new IllegalArgumentException(
                        "Invalid job request, both 'remoteUrl' and 'manifest' should be present with 'remoteJobArchiveRef'.");
            }
            JsonElement jsonElement5 = jsonElement4.getAsJsonObject().get("cpu");
            if (jsonElement3 == null || jsonElement4 == null) {
                throw new IllegalArgumentException(
                        "Invalid job request, 'manifest' should contain 'cpu' for 'remoteJobArchiveRef'.");
            }

            newJob = factory.newJob(jobName, jsonElement2.getAsString(), jsonElement3.getAsString(),
                    jsonElement5.getAsInt());
        } else {
            throw new IllegalArgumentException(
                    "Invalid job request, either 'jobArchiveRef' or 'remoteJobArchiveRef' attribute must be present.");
        }
        synchronized (jobs) {
            jobs.add(newJob);
        }
        return true;
    }

    private boolean nameClash(String jobName) {
        for (BaseJob baseJob : BaseAgent.safeCopy(jobs)) {
            if (jobName.equals(baseJob.getName())) {
                return true;
            }
        }
        return false;
    }
}
