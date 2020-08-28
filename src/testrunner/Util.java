package testrunner;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import testrunner.TestScheduler.JobResult;

public class Util {
    public static class _4Future<T> {
        private Future<Future<Future<Future<T>>>> future;

        public _4Future(Future<Future<Future<Future<T>>>> future) {
            this.future = future;
        }

        public T get() throws InterruptedException, ExecutionException {
            return future.get().get().get().get();
        }
    }

    public static class _3Future<T> {
        private Future<Future<Future<T>>> future;

        public _3Future(Future<Future<Future<T>>> future) {
            this.future = future;
        }

        public T get() throws InterruptedException, ExecutionException {
            return future.get().get().get();
        }
    }

    public static class _2Future<T> {
        private Future<Future<T>> future;

        public _2Future(Future<Future<T>> future) {
            this.future = future;
        }

        public T get() throws InterruptedException, ExecutionException {
            return future.get().get();
        }
    }

    public static _4Future<JobResult> compoundFuture(Future<Future<Future<Future<JobResult>>>> submit) {
        return new _4Future<TestScheduler.JobResult>(submit);
    }
}
