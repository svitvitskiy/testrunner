package testrunner;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.commons.io.FileUtils;

import testrunner.BaseJob.Status;

public class RemoteJob {
    private String name;
    private BaseJob.Status status;
    private String resultArchiveRef;
    private File resultArchive;
    private Callable doneCallback;
    private Object callbackResult;
    private AgentConnection agent;
    private String jobArchiveRef;
    private String manifest;
    private String remoteUrl;
    private int retryCounter;
    private File jobArchive;
    
    public static final RemoteJob WAIT = new RemoteJob("wait", null);

    public RemoteJob(String name, AgentConnection agent) {
        this.name = name;
        this.agent = agent;
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

    public AgentConnection getAgent() {
        return agent;
    }

    public void updateStatus(String status) {
        Status newStatus = BaseJob.Status.valueOf(status);
        boolean fireDoneCallback;
        synchronized (this) {
            fireDoneCallback = this.status != newStatus && newStatus == BaseJob.Status.DONE;
            if (this.status != newStatus) {
                Log.debug("[" + name + "] Status update " + this.status + " -> " + newStatus);
            }
            this.status = newStatus;
        }

        if (fireDoneCallback && doneCallback != null) {
            try {
                Log.debug("[" + name + "] Firing done callback");
                callbackResult = doneCallback.call();
                Log.debug("[" + name + "] Callback done");
            } catch (Exception e) {
                e.printStackTrace(System.out);
            }
        }

        synchronized (this) {
            this.notifyAll();
        }
    }

    public void updateResultArchiveRef(String resultArchiveRef) {
        this.resultArchiveRef = resultArchiveRef;
    }

    public String getResultArchiveRef() {
        return resultArchiveRef;
    }

    public File getResultArchive(HttpIface http) throws IOException {
        if (resultArchive != null)
            return resultArchive;
        File temp = File.createTempFile("stan", "cool");
        URL downloadUrl = new URL(new URL(agent.getUrl()), "/download/" + resultArchiveRef + "?option=delete");
        Log.info("[" + name + "] Downloading result from '" + downloadUrl.toExternalForm() + "'");
        try (InputStream is = http.openUrlStream(downloadUrl)) {
            FileUtils.copyInputStreamToFile(is, temp);
        }
        resultArchive = temp;
        return temp;
    }

    public <T> Future<T> onDone(Callable<T> runnable) {
        if (this.doneCallback != null)
            throw new IllegalStateException("Can set callback only once");

        this.doneCallback = runnable;
        return new Future<T>() {
            @Override
            public boolean cancel(boolean mayInterruptIfRunning) {
                throw new RuntimeException("Not implemented");
            }

            @Override
            public boolean isCancelled() {
                throw new RuntimeException("Not implemented");
            }

            @Override
            public boolean isDone() {
                throw new RuntimeException("Not implemented");
            }

            @Override
            public T get() throws InterruptedException, ExecutionException {
                Log.debug("[" + name + "] Returning callback result");
                waitDone();
                Log.debug("[" + name + "] Returning callback result");
                return (T) callbackResult;
            }

            @Override
            public T get(long timeout, TimeUnit unit)
                    throws InterruptedException, ExecutionException, TimeoutException {
                throw new RuntimeException("Not implemented");
            }
        };
    }

    public void updateJobArchiveRef(String jobArchiveRef) {
        this.jobArchiveRef = jobArchiveRef;
    }

    public void updateManifest(String manifest) {
        this.manifest = manifest;
    }

    public void updateRemoteUrl(String remoteUrl) {
        this.remoteUrl = remoteUrl;
    }

    public String getJobArchiveRef() {
        return jobArchiveRef;
    }

    public String getManifest() {
        return manifest;
    }

    public String getRemoteUrl() {
        return remoteUrl;
    }

    public void incrementRetryCounter() {
        ++retryCounter;
    }

    public int getRetryCounter() {
        return retryCounter;
    }

    public void resetRetryCounter() {
        retryCounter = 0;
    }

    public void updateJobArchive(File jobArchive) {
        this.jobArchive = jobArchive;

    }

    public File getJobArchive() {
        return jobArchive;
    }

    public void setStatus(Status new1) {
        this.status = new1;

    }
}
