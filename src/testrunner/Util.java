package testrunner;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

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

    public static <T> _4Future<T> compoundFuture(Future<Future<Future<Future<T>>>> submit) {
        return new _4Future<T>(submit);
    }
}
