package helper.journey.starsavior;

import android.graphics.Bitmap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** Owns one frame's images, including images still borrowed by asynchronous OCR. */
final class CaptureJob {
    final int generation;
    final Executor callbacks;
    private final CaptureSessionStateMachine session;
    private final Consumer<RuntimeException> failure;
    private final Map<Bitmap, ImageState> images = new IdentityHashMap<>();
    private boolean finished;

    CaptureJob(CaptureSessionStateMachine session, int generation, Executor executor,
               Consumer<RuntimeException> failure) {
        this.session = session;
        this.generation = generation;
        this.failure = failure;
        callbacks = callback -> executor.execute(() -> {
            if (isFinished()) return;
            try { callback.run(); }
            catch (RuntimeException error) { fail(error); }
        });
    }

    synchronized Bitmap own(Bitmap bitmap) {
        if (finished) throw new IllegalStateException("Capture already finished");
        ImageState state = images.computeIfAbsent(bitmap, ignored -> new ImageState());
        if (state.released) throw new IllegalStateException("Image already released");
        state.owners++;
        return bitmap;
    }

    synchronized void beginUse(Bitmap bitmap) {
        ImageState state = images.get(bitmap);
        if (finished || state == null || state.released || state.owners == 0) throw new IllegalStateException("Image unavailable");
        state.users++;
    }

    synchronized void endUse(Bitmap bitmap) {
        ImageState state = images.get(bitmap);
        if (state == null || state.users == 0) throw new IllegalStateException("Unbalanced image use");
        state.users--;
        releaseIfReady(bitmap, state);
    }

    synchronized void release(Bitmap... bitmaps) {
        for (Bitmap bitmap : bitmaps) {
            ImageState state = images.get(bitmap);
            if (state == null) throw new IllegalStateException("Unowned image");
            state.owners = Math.max(0, state.owners - 1);
            releaseIfReady(bitmap, state);
        }
    }

    synchronized boolean finish() {
        if (finished) return false;
        finished = true;
        for (Map.Entry<Bitmap, ImageState> entry : images.entrySet()) {
            entry.getValue().owners = 0;
            releaseIfReady(entry.getKey(), entry.getValue());
        }
        session.finishCapture(generation);
        return true;
    }

    void fail(RuntimeException error) {
        if (finish()) failure.accept(error);
    }

    synchronized boolean isFinished() { return finished; }

    private static void releaseIfReady(Bitmap bitmap, ImageState state) {
        if (!state.released && state.owners == 0 && state.users == 0) {
            state.released = true;
            bitmap.recycle();
        }
    }

    private static final class ImageState {
        int users;
        int owners;
        boolean released;
    }
}
