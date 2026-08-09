package helper.journey.starsavior;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class StaminaGaugeDetectorTest {
    @Test
    public void findsTranslatedCurrentGaugeOnWidePhone() {
        Fixture fixture = Fixture.create(3120, 1440, 0.382f, 65, 65,
                StaminaGaugeDetector.Direction.NONE);
        StaminaGaugeDetector.Result result = fixture.detect(null);
        assertNotNull(result);
        assertEquals(StaminaGaugeDetector.Direction.NONE, result.direction);
        assertNear(65, result.current, 2);
    }

    @Test
    public void separatesRecoveryPreview() {
        Fixture fixture = Fixture.create(3120, 1440, 0.355f, 40, 70,
                StaminaGaugeDetector.Direction.GAIN);
        StaminaGaugeDetector.Result result = fixture.detect(null);
        assertNotNull(result);
        assertEquals(StaminaGaugeDetector.Direction.GAIN, result.direction);
        assertNear(40, result.current, 2);
        assertNear(70, result.after, 2);
    }

    @Test
    public void detectsNarrowRecoveryPreview() {
        Fixture fixture = Fixture.create(3120, 1440, 0.382f, 85, 88,
                StaminaGaugeDetector.Direction.GAIN);
        StaminaGaugeDetector.Result result = fixture.detect(null);
        assertNotNull(result);
        assertEquals(StaminaGaugeDetector.Direction.GAIN, result.direction);
        assertNear(85, result.current, 1);
        assertNear(88, result.after, 1);
    }

    @Test
    public void separatesConsumptionPreviewOnFoldableRatio() {
        Fixture fixture = Fixture.create(1280, 1153, 0.368f, 85, 68,
                StaminaGaugeDetector.Direction.LOSS);
        StaminaGaugeDetector.Result result = fixture.detect(null);
        assertNotNull(result);
        assertEquals(StaminaGaugeDetector.Direction.LOSS, result.direction);
        assertNear(85, result.current, 3);
        assertNear(68, result.after, 3);
    }

    @Test
    public void recognizesFullAndEmptyTracks() {
        StaminaGaugeDetector.Result full = Fixture.create(3120, 1440, 0.375f, 100, 100,
                StaminaGaugeDetector.Direction.NONE).detect(null);
        StaminaGaugeDetector.Result empty = Fixture.create(1280, 1153, 0.368f, 0, 0,
                StaminaGaugeDetector.Direction.NONE).detect(null);
        assertNotNull(full);
        assertNotNull(empty);
        assertEquals(100, full.current);
        assertEquals(0, empty.current);
    }

    @Test
    public void stabilizesCurrentWithoutMergingDifferentPreviewAmounts() {
        StaminaGaugeDetector.Result first = new StaminaGaugeDetector.Result(85, 68,
                StaminaGaugeDetector.Direction.LOSS, new StaminaGaugeDetector.Anchor(0.35f, 0.032f), 0.8f);
        StaminaGaugeDetector.Result second = new StaminaGaugeDetector.Result(85, 69,
                StaminaGaugeDetector.Direction.LOSS, new StaminaGaugeDetector.Anchor(0.35f, 0.032f), 0.8f)
                .stabilize(first);
        assertEquals(85, second.current);
        assertEquals(69, second.after);
    }

    @Test
    public void copiesOnlyASmallTopStrip() {
        StaminaGaugeDetector.Region phone = StaminaGaugeDetector.scanRegion(3120, 1440);
        StaminaGaugeDetector.Region fold = StaminaGaugeDetector.scanRegion(1280, 1153);
        assertTrue(phone.width * phone.height < 250_000);
        assertTrue(fold.width * fold.height < 50_000);
    }

    private static void assertNear(int expected, int actual, int tolerance) {
        assertTrue("expected " + expected + " but was " + actual,
                Math.abs(expected - actual) <= tolerance);
    }

    private static final class Fixture {
        final int width;
        final int height;
        final StaminaGaugeDetector.Region region;
        final int[] pixels;

        private Fixture(int width, int height, StaminaGaugeDetector.Region region, int[] pixels) {
            this.width = width; this.height = height; this.region = region; this.pixels = pixels;
        }

        static Fixture create(int width, int height, float leftRatio, int current, int after,
                              StaminaGaugeDetector.Direction direction) {
            StaminaGaugeDetector.Region region = StaminaGaugeDetector.scanRegion(width, height);
            int[] pixels = new int[region.width * region.height];
            for (int index = 0; index < pixels.length; index++) pixels[index] = rgb(22, 24, 31);

            double scale = width / 3120.0;
            int trackWidth = Math.max(72, (int) Math.round(428 * scale));
            int trackHeight = Math.max(8, (int) Math.round(33 * scale));
            int left = Math.round(width * leftRatio);
            int centerY = (int) Math.round(100 * scale);
            int top = centerY - trackHeight / 2;
            int bottom = top + trackHeight;
            int currentBoundary = Math.round(trackWidth * current / 100f);
            int afterBoundary = Math.round(trackWidth * after / 100f);

            for (int y = top; y < bottom; y++) {
                for (int offset = 0; offset < trackWidth; offset++) {
                    int color;
                    if (direction == StaminaGaugeDetector.Direction.GAIN) {
                        if (offset < currentBoundary) color = normal(offset, trackWidth);
                        else if (offset < afterBoundary) color = rgb(150 + offset * 80 / trackWidth, 250, 150);
                        else color = rgb(78, 78, 78);
                    } else if (direction == StaminaGaugeDetector.Direction.LOSS) {
                        if (offset < afterBoundary) color = normal(offset, trackWidth);
                        else if (offset < currentBoundary) color = rgb(61, 75, 50);
                        else color = rgb(78, 78, 78);
                    } else {
                        color = offset < currentBoundary ? normal(offset, trackWidth) : rgb(78, 78, 78);
                    }
                    set(pixels, region, left + offset, y, color);
                }
            }
            return new Fixture(width, height, region, pixels);
        }

        StaminaGaugeDetector.Result detect(StaminaGaugeDetector.Anchor anchor) {
            return StaminaGaugeDetector.detect(width, height, region, pixels, anchor);
        }

        private static int normal(int offset, int width) {
            float progress = offset / (float) Math.max(1, width - 1);
            return rgb(18 + Math.round(progress * 160),
                    155 + Math.round(progress * 85), 138 - Math.round(progress * 40));
        }

        private static void set(int[] pixels, StaminaGaugeDetector.Region region,
                                int x, int y, int color) {
            if (x < region.left || x >= region.left + region.width
                    || y < region.top || y >= region.top + region.height) return;
            pixels[(y - region.top) * region.width + x - region.left] = color;
        }

        private static int rgb(int red, int green, int blue) {
            return 0xff000000 | (red << 16) | (green << 8) | blue;
        }
    }
}
