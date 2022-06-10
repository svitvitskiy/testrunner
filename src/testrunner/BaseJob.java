package testrunner;

import java.io.IOException;
import java.io.InputStream;

import testrunner.HttpIface.HttpIfaceException;

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
    private int priority;
    private int cpuReq;
    
    public static interface JobFactory {
        BaseJob newJob(String name, String jobArchiveRef, int priority);
        BaseJob newJob(String name, String remoteJobArchiveRef, String remoteUrl, int cpuReq, int priority);
    }

    protected BaseJob(String name, String jobArchiveRef, int priority) {
        this.name = name;
        this.jobArchiveRef = jobArchiveRef;
        this.status = Status.NEW;
        this.cpuReq = 1;
        this.priority = priority;
    }
    
    protected BaseJob(String name, String remoteJobArchiveRef, String remoteUrl, int cpuReq, int priority) {
        this.name = name;
        this.remoteJobArchiveRef = remoteJobArchiveRef;
        this.remoteUrl = remoteUrl;
        this.status = Status.NEW;
        this.cpuReq = cpuReq;
        this.priority = priority;
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
    
    public int getPriority() {
        return priority;
    }
    
    public abstract InputStream getLog() throws IOException, HttpIfaceException;

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
