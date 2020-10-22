package testrunner;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;

import org.apache.http.HttpResponse;
import org.apache.http.util.EntityUtils;

public class BalancingJob extends BaseJob {
    private RemoteJob delegate;
    private boolean downloading;
    private HttpIface http;

    public BalancingJob(String name, String jobArchiveRef, int priority, HttpIface http) {
        super(name, jobArchiveRef, priority);
        this.http = http;
    }

    public boolean hasDelegate() {
        return delegate != null;
    }

    public void updateDelegate(RemoteJob remoteJob) {
        this.delegate = remoteJob;
    }

    public RemoteJob getDelegate() {
        return delegate;
    }

    public boolean isDownloading() {
        return downloading;
    }

    public void setDownloading(boolean downloading) {
        this.downloading = downloading;
    }

    public void eraseDelegate() {
        this.delegate = null;
        this.downloading = false;
    }

    @Override
    public InputStream getLog() throws IOException {
        URL url = new URL(new URL(this.delegate.getAgent().getUrl()), "/log/" + delegate.getName());
        Log.info("[" + getName() + "] Proxying log file from remote agent at: '" + url.toExternalForm() + "'");
        HttpResponse response = http.openConnection(url);
        if (response.getStatusLine().getStatusCode() == 404) {
            EntityUtils.consume(response.getEntity());
            return null;
        } else {
            return response.getEntity().getContent();
        }
    }
}
