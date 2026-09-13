package helper.journey.starsavior;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

/** Keeps capture cleanup reachable when an OCR completion races with service shutdown. */
final class CaptureCallbackExecutor implements Executor {
    private final ExecutorService worker;

    CaptureCallbackExecutor(ExecutorService worker) {
        this.worker = worker;
    }

    @Override
    public void execute(Runnable callback) {
        Completion completion = new Completion(callback);
        try {
            worker.execute(completion);
        } catch (RejectedExecutionException stopped) {
            // The owner invalidates its capture session before shutting down. A late
            // callback must still run its stale-session cleanup instead of leaking images.
            if (!worker.isShutdown()) throw stopped;
            completion.run();
        }
    }

    void shutdownNow() {
        for (Runnable pending : worker.shutdownNow()) {
            // Normal DB work remains canceled; only capture completions own resources
            // that must be released even when they never reached the worker thread.
            if (pending instanceof Completion) pending.run();
        }
    }

    private static final class Completion implements Runnable {
        private final Runnable callback;

        Completion(Runnable callback) { this.callback = callback; }

        @Override public void run() { callback.run(); }
    }
}
