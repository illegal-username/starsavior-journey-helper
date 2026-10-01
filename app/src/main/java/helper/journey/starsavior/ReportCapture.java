package helper.journey.starsavior;

import android.graphics.Bitmap;
import java.time.Instant;

/** One read-only borrower of the OCR frame, shared by the result and email preparation. */
final class ReportCapture {
    final Bitmap bitmap;
    final int generation;
    final String capturedAt;
    private final Runnable releaseImage;
    private int references = 1;

    ReportCapture(CaptureJob job, Bitmap bitmap) {
        job.beginUse(bitmap);
        this.bitmap = bitmap;
        generation = job.generation;
        capturedAt = Instant.now().toString();
        releaseImage = () -> job.endUse(bitmap);
    }

    synchronized ReportCapture acquire() {
        if (references == 0) throw new IllegalStateException("Capture released");
        references++;
        return this;
    }

    synchronized void release() {
        if (references == 0) throw new IllegalStateException("Capture already released");
        if (--references == 0) releaseImage.run();
    }
}
