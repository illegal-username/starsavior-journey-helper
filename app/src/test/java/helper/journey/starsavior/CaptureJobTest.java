package helper.journey.starsavior;

import android.graphics.Bitmap;
import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class CaptureJobTest {
    private CaptureSessionStateMachine session() {
        CaptureSessionStateMachine session = new CaptureSessionStateMachine();
        session.activate(); assertTrue(session.beginCapture());
        return session;
    }
    private Bitmap bitmap() { return Bitmap.createBitmap(12, 12, Bitmap.Config.ARGB_8888); }

    @Test public void finishWaitsForAllOcrBorrowersAndReleasesAliasesOnlyOnce() {
        CaptureSessionStateMachine session = session();
        CaptureJob job = new CaptureJob(session, session.generation(), Runnable::run, error -> fail());
        Bitmap shared = job.own(bitmap()), other = job.own(bitmap());
        assertSame(shared, job.own(shared));
        job.beginUse(shared); job.beginUse(shared);
        job.release(shared, shared);
        assertFalse(shared.isRecycled());
        assertTrue(job.finish()); assertFalse(job.finish());
        assertTrue(other.isRecycled()); assertFalse(shared.isRecycled());
        job.endUse(shared); assertFalse(shared.isRecycled());
        job.endUse(shared); assertTrue(shared.isRecycled());
        assertEquals(CaptureSessionStateMachine.Phase.ACTIVE, session.phase());
    }

    @Test public void completingACropCannotRecycleASharedFullFrameNeededForFallback() {
        CaptureSessionStateMachine session = session();
        CaptureJob job = new CaptureJob(session, session.generation(), Runnable::run, error -> fail());
        Bitmap full = job.own(bitmap());
        Bitmap crop = job.own(full);
        job.beginUse(crop);
        job.release(crop);
        job.endUse(crop);
        assertFalse("Full-frame fallback still owns the shared object", full.isRecycled());
        job.beginUse(full); job.endUse(full);
        job.finish(); assertTrue(full.isRecycled());
    }

    @Test public void obsoleteCompletionsCannotFinishNewCaptureOrRestoreRevokedSession() {
        for (int boundary = 0; boundary < 3; boundary++) {
            CaptureSessionStateMachine session = session();
            CaptureJob job = new CaptureJob(session, session.generation(), Runnable::run, error -> fail());
            Bitmap image = job.own(bitmap()); job.beginUse(image);
            if (boundary == 0) session.destroy();
            else if (boundary == 1) session.waitForPermission();
            else { session.interruptForResize(); assertTrue(session.beginCapture()); }
            CaptureSessionStateMachine.Phase phase = session.phase();
            int generation = session.generation();
            job.finish(); job.endUse(image);
            assertTrue(image.isRecycled());
            assertEquals(phase, session.phase()); assertEquals(generation, session.generation());
        }
    }

    @Test public void exceptionInsideQueuedCallbackCleansUpAndReportsOnlyOnce() {
        CaptureSessionStateMachine session = session();
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        AtomicInteger failures = new AtomicInteger();
        CaptureJob job = new CaptureJob(session, session.generation(), queue::add,
                error -> failures.incrementAndGet());
        Bitmap image = job.own(bitmap());
        job.callbacks.execute(() -> { throw new IllegalStateException("synthetic callback failure"); });
        job.callbacks.execute(() -> fail("Late callback must not read released images"));
        while (!queue.isEmpty()) queue.remove().run();
        job.fail(new IllegalStateException("duplicate"));
        assertEquals(1, failures.get()); assertTrue(image.isRecycled());
        assertEquals(CaptureSessionStateMachine.Phase.ACTIVE, session.phase());
    }
}
