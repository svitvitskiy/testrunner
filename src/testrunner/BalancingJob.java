package testrunner;

public class BalancingJob extends BaseJob {
    private RemoteJob delegate;
    private boolean downloading;
    
    public BalancingJob(String name, String jobArchiveRef) {
        super(name, jobArchiveRef);
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
}
