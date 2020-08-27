package testrunner;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;

import org.apache.commons.io.FileUtils;

import testrunner.BaseJob.Status;

public class RemoteJob {
    private String name;
    private BaseJob.Status status;
    private String resultArchiveRef;
    private String agentUrl;
    private File resultArchive;

    public RemoteJob(String name, String url) {
        this.name = name;
        this.agentUrl = url;
        this.status = Status.NEW;
    }

    public synchronized void waitDone() throws InterruptedException {
        while (this.status != BaseJob.Status.DONE) {
            this.wait();
        }
    }

    public LeafJob.Status getStatus() {
        return status;
    }

    public String getName() {
        return name;
    }
    
    public String getAgentUrl() {
        return agentUrl;
    }

    public synchronized void updateStatus(String status) {
        this.status = BaseJob.Status.valueOf(status);
        this.notifyAll();
    }

    public void updateResultArchiveRef(String resultArchiveRef) {
        this.resultArchiveRef = resultArchiveRef;
    }

    public String getResultArchiveRef() {
        return resultArchiveRef;
    }

    public File getResultArchive() throws IOException {
        if (resultArchive != null)
            return resultArchive;
        File temp = File.createTempFile("stan", "cool");
        URL downloadUrl = new URL(new URL(agentUrl), "/download/" + resultArchiveRef);
        System.out.println("INFO: [" + name + "] Downloading result from '" + downloadUrl.toExternalForm() + "'");
        try (InputStream is = downloadUrl.openStream()) {
            FileUtils.copyInputStreamToFile(is, temp);
        }
        resultArchive = temp;
        return temp;
    }
}
