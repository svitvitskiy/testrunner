package testrunner;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.servlet.http.HttpServletResponse;

import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.conn.HttpClientConnectionManager;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.params.HttpConnectionParams;
import org.apache.http.params.HttpParams;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import testrunner.TestScheduler.JobResult;

public class Util {

    public static class CompoundFuture<T> implements Future<T> {
        private Future<Future<T>> future;

        public CompoundFuture(Future<Future<T>> future) {
            this.future = future;
        }

        public T get() throws InterruptedException, ExecutionException {
            Future<T> future2 = future.get();
            if (future2 == null)
                return null;
            return future2.get();
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            if (future.isDone()) {
                try {
                    Future<T> res = future.get();
                    res.cancel(mayInterruptIfRunning);
                } catch (InterruptedException | ExecutionException e) {
                }
            } else {
                future.cancel(mayInterruptIfRunning);
            }
            return false;
        }

        @Override
        public boolean isCancelled() {
            if (future.isCancelled())
                return true;
            if (future.isDone()) {
                try {
                    Future<T> res = future.get();
                    return res.isCancelled();
                } catch (InterruptedException | ExecutionException e) {
                }
            }
            return false;
        }

        @Override
        public boolean isDone() {
            if (!future.isDone())
                return false;
            try {
                Future<T> res = future.get();
                return res.isDone();
            } catch (InterruptedException | ExecutionException e) {
            }
            return false;
        }

        @Override
        public T get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
            Future<T> future2 = future.get(timeout, unit);
            if (future2 == null)
                return null;
            return future2.get(timeout, unit);
        }
    }

    public static <T> Future<T> compoundFuture4(Future<Future<Future<Future<T>>>> submit) {
        return compoundFuture2(compoundFuture3(submit));
    }

    public static <T> Future<T> compoundFuture3(Future<Future<Future<T>>> submit) {
        return compoundFuture2(compoundFuture2(submit));
    }

    public static <T> CompoundFuture<T> compoundFuture2(Future<Future<T>> submit) {
        return new CompoundFuture<T>(submit);
    }

    public static Future<JobResult> compoundFuture5(Future<Future<Future<Future<Future<JobResult>>>>> submit) {
        return compoundFuture3(compoundFuture3(submit));
    }

    public static class DummyFuture<T> implements Future<T> {

        private T result;

        public DummyFuture(T t) {
            this.result = t;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return false;
        }

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public boolean isDone() {
            return true;
        }

        @Override
        public T get() throws InterruptedException, ExecutionException {
            return result;
        }

        @Override
        public T get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
            return result;
        }
    }

    public static <T> List<T> safeCopy(List<T> unsafe) {
        List<T> throwaway = new ArrayList<T>();
        synchronized (unsafe) {
            throwaway.addAll(unsafe);
        }
        return throwaway;
    }

    public static <T> List<T> copy(List<T> unsafe) {
        List<T> throwaway = new ArrayList<T>();
        throwaway.addAll(unsafe);
        return throwaway;
    }

    public static <T> Future<T> dummyFuture(T t) {
        return new DummyFuture<T>(t);
    }

    public static <T> Future<Future<T>> dummyFuture2(T t) {
        return new DummyFuture<Future<T>>(new DummyFuture<T>(t));
    }

    public static <T> Future<Future<Future<T>>> dummyFuture3(T t) {
        return new DummyFuture<Future<Future<T>>>(new DummyFuture<Future<T>>(new DummyFuture<T>(t)));
    }

    public static <T> Future<Future<Future<Future<T>>>> dummyFuture4(T t) {
        return new DummyFuture<Future<Future<Future<T>>>>(
                new DummyFuture<Future<Future<T>>>(new DummyFuture<Future<T>>(new DummyFuture<T>(t))));
    }

    public String getVersion() {
        Properties props = new Properties();
        try (InputStream is = this.getClass().getClassLoader().getResourceAsStream("git.properties")) {
            if (is == null)
                return "N/A";
            props.load(is);
            return props.getProperty("git.build.version") + "(" + props.getProperty("git.commit.id.abbrev")
                    + "). Build on " + props.getProperty("git.build.time") + ".";
        } catch (Throwable t) {
            return "N/A";
        }
    }

    public static void processTemplate(HttpServletResponse response, Context context, String template)
            throws IOException {
        TemplateEngine templateEngine = new TemplateEngine();
        ClassLoaderTemplateResolver templateResolver = new ClassLoaderTemplateResolver();
        templateResolver.setTemplateMode("HTML");
        templateEngine.setTemplateResolver(templateResolver);
        templateEngine.process(template, context, response.getWriter());
    }
}
