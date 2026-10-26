package app.opencodepair;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Runs blocking API work off the main thread and delivers the result back on it. */
public final class Bg {
    public interface Ok<T> { void run(T value); }
    public interface Err { void run(Exception e); }

    private static final ExecutorService POOL = Executors.newCachedThreadPool();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private Bg() {}

    public static <T> void run(Callable<T> task, Ok<T> ok, Err err) {
        POOL.execute(() -> {
            try {
                T value = task.call();
                MAIN.post(() -> ok.run(value));
            } catch (Exception e) {
                MAIN.post(() -> err.run(e));
            }
        });
    }
}
