package helper.journey.starsavior;

/** Chooses OCR regions for both wide phones and near-square foldable screens. */
final class CaptureRegionPlanner {
    static final class Region {
        final int left;
        final int top;
        final int right;
        final int bottom;

        Region(int left, int top, int right, int bottom) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }

        int width() { return right - left; }
        int height() { return bottom - top; }
        boolean contains(int x, int y) {
            return x >= left && x < right && y >= top && y < bottom;
        }
    }

    private CaptureRegionPlanner() {}

    static Region choice(int width, int height) {
        boolean tallLayout = width / (double) height < 1.45;
        return region(width, height,
                tallLayout ? 0.44 : 0.48,
                tallLayout ? 0.07 : 0.14,
                0.995,
                tallLayout ? 0.95 : 0.88);
    }

    static Region event(int width, int height) {
        boolean tallLayout = width / (double) height < 1.45;
        return region(width, height,
                tallLayout ? 0.04 : 0.095,
                tallLayout ? 0.06 : 0.13,
                tallLayout ? 0.50 : 0.46,
                tallLayout ? 0.35 : 0.28);
    }

    private static Region region(int width, int height, double leftRatio, double topRatio,
                                 double rightRatio, double bottomRatio) {
        int left = clamp((int) Math.floor(width * leftRatio), 0, width - 1);
        int top = clamp((int) Math.floor(height * topRatio), 0, height - 1);
        int right = clamp((int) Math.ceil(width * rightRatio), left + 1, width);
        int bottom = clamp((int) Math.ceil(height * bottomRatio), top + 1, height);
        return new Region(left, top, right, bottom);
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
