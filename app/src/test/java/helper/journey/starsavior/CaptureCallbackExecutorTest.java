package helper.journey.starsavior;

import static org.junit.Assert.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public final class CaptureCallbackExecutorTest {
    @Test public void activeCallbacksUseTheWorker() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        CaptureCallbackExecutor callbacks = new CaptureCallbackExecutor(worker);
        AtomicReference<Thread> ranOn = new AtomicReference<>();
        try {
            callbacks.execute(() -> ranOn.set(Thread.currentThread()));
            worker.submit(() -> {}).get(5, TimeUnit.SECONDS);
            assertNotNull(ranOn.get());
            assertNotSame(Thread.currentThread(), ranOn.get());
        } finally { callbacks.shutdownNow(); }
    }

    @Test public void queuedCaptureCleanupSurvivesShutdownWhileOrdinaryWorkIsCanceled() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        CaptureCallbackExecutor callbacks = new CaptureCallbackExecutor(worker);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch block = new CountDownLatch(1);
        AtomicInteger cleaned = new AtomicInteger();
        AtomicInteger ordinary = new AtomicInteger();
        worker.execute(() -> {
            entered.countDown();
            try { block.await(); } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
        });
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            callbacks.execute(cleaned::incrementAndGet);
            worker.execute(ordinary::incrementAndGet);
            callbacks.shutdownNow();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(1, cleaned.get());
            assertEquals(0, ordinary.get());
            callbacks.shutdownNow();
            assertEquals(1, cleaned.get());
        } finally { block.countDown(); callbacks.shutdownNow(); }
    }

    @Test public void completionArrivingAfterShutdownStillRunsCleanup() {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        CaptureCallbackExecutor callbacks = new CaptureCallbackExecutor(worker);
        callbacks.shutdownNow();
        AtomicInteger cleaned = new AtomicInteger();
        callbacks.execute(cleaned::incrementAndGet);
        assertEquals(1, cleaned.get());
    }
}
