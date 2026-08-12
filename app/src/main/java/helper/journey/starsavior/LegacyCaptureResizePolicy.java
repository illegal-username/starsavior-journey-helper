package helper.journey.starsavior;

/** Limits the pre-Android 14 capture workaround to an actual quarter-turn mismatch. */
final class LegacyCaptureResizePolicy {
    private static final int CONTENT_RESIZE_CALLBACK_API = 34;

    private LegacyCaptureResizePolicy() {}

    static boolean shouldResize(int sdkInt, int captureWidth, int captureHeight,
                                int displayWidth, int displayHeight) {
        if (sdkInt >= CONTENT_RESIZE_CALLBACK_API
                || captureWidth <= 0 || captureHeight <= 0
                || displayWidth <= 0 || displayHeight <= 0) {
            return false;
        }
        int captureOrientation = orientation(captureWidth, captureHeight);
        int displayOrientation = orientation(displayWidth, displayHeight);
        return captureOrientation != 0 && displayOrientation != 0
                && captureOrientation != displayOrientation;
    }

    private static int orientation(int width, int height) {
        if (width == height) return 0;
        return width > height ? 1 : -1;
    }
}
