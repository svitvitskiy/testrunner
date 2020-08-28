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
    private String agentUrl;
    private File resultArchive;
    private Callable doneCallback;
    private Object callbackResult;

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

    public void updateStatus(String status) {
        Status newStatus = BaseJob.Status.valueOf(status);
        boolean fireDoneCallback;
        synchronized (this) {
            fireDoneCallback = this.status != newStatus && newStatus == BaseJob.Status.DONE;
            if (this.status != newStatus) {
                System.out.println("  DEBUG [" + name + "] Status update " + this.status + " -> " + newStatus);
            }
            this.status = newStatus;
        }

        if (fireDoneCallback && doneCallback != null) {
            try {
                System.out.println("  DEBUG [" + name + "] Firing done callback");
                callbackResult = doneCallback.call();
                System.out.println("  DEBUG [" + name + "] Callback done");
            } catch (Exception e) {
                e.printStackTrace();
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

    public File getResultArchive() throws IOException {
        if (resultArchive != null)
            return resultArchive;
        File temp = File.createTempFile("stan", "cool");
        URL downloadUrl = new URL(new URL(agentUrl), "/download/" + resultArchiveRef + "?option=delete");
        System.out.println("INFO: [" + name + "] Downloading result from '" + downloadUrl.toExternalForm() + "'");
        try (InputStream is = downloadUrl.openStream()) {
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
                System.out.println(
                        (char) 27 + "[95m  DEBUG [" + name + "] Returning callback result" + (char) 27 + "[0m");
                waitDone();
                System.out.println("  DEBUG [" + name + "] Returning callback result");
                return (T) callbackResult;
            }

            @Override
            public T get(long timeout, TimeUnit unit)
                    throws InterruptedException, ExecutionException, TimeoutException {
                throw new RuntimeException("Not implemented");
            }
        };
    }
}
