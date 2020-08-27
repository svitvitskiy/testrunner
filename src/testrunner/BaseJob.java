package testrunner;

public abstract class BaseJob {
    public static enum Status {
        NEW, PENDING, PROCESSING, DONE, ERROR
    }

    private String jobArchiveRef;
    private String resultArchiveRef;
    private Status status;
    private String name;
    private String remoteJobArchiveRef;
    private String remoteUrl;
    private int cpuReq;
    
    public static interface JobFactory {
        BaseJob newJob(String name, String jobArchiveRef);
        BaseJob newJob(String name, String remoteJobArchiveRef, String remoteUrl, int cpuReq);
    }

    protected BaseJob(String name, String jobArchiveRef) {
        this.name = name;
        this.jobArchiveRef = jobArchiveRef;
        this.status = Status.NEW;
        this.cpuReq = 1;
    }
    
    protected BaseJob(String name, String remoteJobArchiveRef, String remoteUrl, int cpuReq) {
        this.name = name;
        this.remoteJobArchiveRef = remoteJobArchiveRef;
        this.remoteUrl = remoteUrl;
        this.status = Status.NEW;
        this.cpuReq = cpuReq;
    }

    public String getJobArchiveRef() {
        return jobArchiveRef;
    }

    public Status getStatus() {
        return status;
    }

    public String getResultArchiveRef() {
        return resultArchiveRef;
    }

    public String getName() {
        return name;
    }

    public String getRemoteJobArchiveRef() {
        return remoteJobArchiveRef;
    }

    public String getRemoteUrl() {
        return remoteUrl;
    }

    public int getCpuReq() {
        return cpuReq;
    }

    protected void updateResultArchiveRef(String resultArchiveRef) {
        this.resultArchiveRef = resultArchiveRef;

    }

    protected void updateStatus(Status status) {
        this.status = status;
    }
    
    protected void updateCpuReq(int cpuReq) {
        this.cpuReq = cpuReq;
    }
    
    protected void updateJobArchiveRef(String jobArchiveRef) {
        this.jobArchiveRef = jobArchiveRef;
    }
}
