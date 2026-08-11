package helper.journey.starsavior;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
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
    public void separatesRecoveryPreviewFromCompletelyEmptyGauge() {
        Fixture fixture = Fixture.create(2340, 1080, 0.385f, 0, 17,
                StaminaGaugeDetector.Direction.GAIN);

        StaminaGaugeDetector.Result result = fixture.detect(null);

        assertNotNull(result);
        assertEquals(StaminaGaugeDetector.Direction.GAIN, result.direction);
        assertEquals(0, result.current);
        assertNear(17, result.after, 2);
    }

    @Test
    public void separatesRecoveryPreviewEndingAtFullGauge() {
        Fixture fixture = Fixture.create(2340, 1080, 0.385f, 83, 100,
                StaminaGaugeDetector.Direction.GAIN);

        StaminaGaugeDetector.Result result = fixture.detect(null);

        assertNotNull(result);
        assertEquals(StaminaGaugeDetector.Direction.GAIN, result.direction);
        assertNear(83, result.current, 2);
        assertEquals(100, result.after);
    }

    @Test
    public void keepsFullWidthWhenRecoveryPreviewFadesToPaleYellow() {
        Fixture fixture = Fixture.create(3120, 1440, 0.385f, 37, 100,
                StaminaGaugeDetector.Direction.GAIN);
        fixture.paintPaleYellowGainPreview(0.385f, 37, 100);

        StaminaGaugeDetector.Result result = fixture.detect(null);

        assertNotNull(result);
        assertEquals(StaminaGaugeDetector.Direction.GAIN, result.direction);
        assertNear(37, result.current, 2);
        assertEquals(100, result.after);
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
    public void separatesConsumptionPreviewFromCompletelyFullGauge() {
        Fixture fixture = Fixture.create(2340, 1080, 0.385f, 100, 83,
                StaminaGaugeDetector.Direction.LOSS);

        StaminaGaugeDetector.Result result = fixture.detect(null);

        assertNotNull(result);
        assertEquals(StaminaGaugeDetector.Direction.LOSS, result.direction);
        assertEquals(100, result.current);
        assertNear(83, result.after, 2);
    }

    @Test
    public void separatesConsumptionPreviewEndingAtEmptyGauge() {
        Fixture fixture = Fixture.create(2340, 1080, 0.385f, 17, 0,
                StaminaGaugeDetector.Direction.LOSS);

        StaminaGaugeDetector.Result result = fixture.detect(null);

        assertNotNull(result);
        assertEquals(StaminaGaugeDetector.Direction.LOSS, result.direction);
        assertNear(17, result.current, 2);
        assertEquals(0, result.after);
    }

    @Test
    public void separatesDimConsumptionPreviewEndingAtEmptyGauge() {
        Fixture fixture = Fixture.createWithLossColor(2340, 1080, 0.385f, 17, 0,
                Fixture.rgb(40, 56, 53));

        StaminaGaugeDetector.Result result = fixture.detect(null);

        assertNotNull(result);
        assertEquals(StaminaGaugeDetector.Direction.LOSS, result.direction);
        assertNear(17, result.current, 2);
        assertEquals(0, result.after);
    }

    @Test
    public void separatesBlueShiftedConsumptionPreviewEndingAtEmptyGauge() {
        Fixture fixture = Fixture.createWithLossColor(2340, 1080, 0.385f, 17, 0,
                Fixture.rgb(40, 55, 61));

        StaminaGaugeDetector.Result result = fixture.detect(null);

        assertNotNull(result);
        assertEquals(StaminaGaugeDetector.Direction.LOSS, result.direction);
        assertNear(17, result.current, 2);
        assertEquals(0, result.after);
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
    public void ignoresStaleAnchorAndGreenScenery() {
        Fixture fixture = Fixture.create(2400, 1080, 0.387f, 36, 36,
                StaminaGaugeDetector.Direction.NONE);
        fixture.paintGreenScenery(0.335f);

        StaminaGaugeDetector.Result result = fixture.detect(
                new StaminaGaugeDetector.Anchor(0.28f, 0.025f));

        assertNotNull(result);
        assertNear(36, result.current, 2);
        assertTrue("detector kept the stale scenery anchor",
                result.anchor.leftRatio > 0.38f && result.anchor.leftRatio < 0.40f);
    }

    @Test
    public void rejectsLowSaturationSceneryWithGaugeGeometry() {
        Fixture fixture = Fixture.blank(2340, 1080);
        fixture.paintLowSaturationBand(0.385f);

        assertNull(fixture.detect(null));
    }

    @Test
    public void rejectsColoredCandidateClippedByScanRegion() {
        Fixture fixture = Fixture.blank(2340, 1080);
        fixture.paintClippedGaugeBand();

        assertNull(fixture.detect(null));
    }

    @Test
    public void prefersPartialGaugeOverThinSameRowStatusText() {
        Fixture fixture = Fixture.create(2340, 1080, 0.385f, 17, 17,
                StaminaGaugeDetector.Direction.NONE);
        fixture.paintThinSameRowDecoy();

        StaminaGaugeDetector.Result result = fixture.detect(null);

        assertNotNull(result);
        assertEquals(StaminaGaugeDetector.Direction.NONE, result.direction);
        assertNear(17, result.current, 2);
        assertTrue("detector selected thin status text instead of the gauge",
                result.anchor.leftRatio > 0.37f && result.anchor.leftRatio < 0.40f);
    }

    @Test
    public void rejectsThinSameRowStatusTextWithoutGauge() {
        Fixture fixture = Fixture.blank(2340, 1080);
        fixture.paintThinSameRowDecoy();

        assertNull(fixture.detect(null));
    }

    @Test
    public void prefersFullGaugeOverSaturatedBannerAboveHud() {
        Fixture fixture = Fixture.create(2340, 1080, 0.385f, 100, 100,
                StaminaGaugeDetector.Direction.NONE);
        fixture.paintUpperBannerDecoy();

        StaminaGaugeDetector.Result result = fixture.detect(null);

        assertNotNull(result);
        assertEquals(StaminaGaugeDetector.Direction.NONE, result.direction);
        assertEquals(100, result.current);
        assertTrue("detector selected the upper banner instead of the gauge",
                result.anchor.leftRatio > 0.37f && result.anchor.leftRatio < 0.40f);
    }

    @Test
    public void followsVerticallyShiftedGaugeInsteadOfReferenceRow() {
        Fixture fixture = Fixture.create(2400, 1080, 0.387f, 36, 36,
                StaminaGaugeDetector.Direction.NONE, 18);

        StaminaGaugeDetector.Result result = fixture.detect(null);

        assertNotNull(result);
        assertNear(36, result.current, 2);
        assertTrue("detector forced the gauge back to the reference row",
                result.anchor.centerYRatio > 0.037f);
    }

    @Test
    public void fallsBackToGaugeBelowPreferredHudBand() {
        Fixture fixture = Fixture.create(2400, 1080, 0.387f, 52, 52,
                StaminaGaugeDetector.Direction.NONE, 50);

        StaminaGaugeDetector.Result result = fixture.detect(null);

        assertNotNull(result);
        assertNear(52, result.current, 2);
        assertTrue("detector rejected a vertically translated gauge",
                result.anchor.centerYRatio > 0.050f);
    }

    @Test
    public void readsSameGaugeAtIndependentHudScalesAndTranslations() {
        int[] widths = {428, 497, 577, 640};
        int[] heights = {30, 34, 39, 43};
        float[] lefts = {0.388f, 0.340f, 0.353f, 0.331f};
        for (int index = 0; index < widths.length; index++) {
            Fixture fixture = Fixture.createCustom(3120, 1440, lefts[index],
                    33, 64, StaminaGaugeDetector.Direction.GAIN,
                    widths[index], heights[index], index * 5 - 6);
            StaminaGaugeDetector.Result result = fixture.detect(null);
            assertNotNull(result);
            assertEquals(StaminaGaugeDetector.Direction.GAIN, result.direction);
            assertNear(33, result.current, 1);
            assertNear(64, result.after, 1);
        }
    }

    @Test
    public void keepsPhysicalWidthWhenColoredCoreIsVerticallyCompressed() {
        Fixture fixture = Fixture.createCustom(2340, 1080, 0.385f,
                17, 50, StaminaGaugeDetector.Direction.GAIN,
                320, 16, 0);

        StaminaGaugeDetector.Result result = fixture.detect(null);

        assertNotNull(result);
        assertEquals(StaminaGaugeDetector.Direction.GAIN, result.direction);
        assertNear(17, result.current, 3);
        assertNear(50, result.after, 3);
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
        assertTrue(phone.width * phone.height < 300_000);
        assertTrue(fold.width * fold.height < 60_000);
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
            return create(width, height, leftRatio, current, after, direction, 0);
        }

        static Fixture create(int width, int height, float leftRatio, int current, int after,
                              StaminaGaugeDetector.Direction direction, int centerYOffset) {
            double scale = width / 3120.0;
            int trackWidth = Math.max(72, (int) Math.round(428 * scale));
            int trackHeight = Math.max(8, (int) Math.round(33 * scale));
            return createCustom(width, height, leftRatio, current, after, direction,
                    trackWidth, trackHeight, centerYOffset);
        }

        static Fixture createWithLossColor(int width, int height, float leftRatio,
                                           int current, int after, int lossColor) {
            double scale = width / 3120.0;
            int trackWidth = Math.max(72, (int) Math.round(428 * scale));
            int trackHeight = Math.max(8, (int) Math.round(33 * scale));
            return createCustom(width, height, leftRatio, current, after,
                    StaminaGaugeDetector.Direction.LOSS,
                    trackWidth, trackHeight, 0, lossColor);
        }

        static Fixture createCustom(int width, int height, float leftRatio, int current, int after,
                                    StaminaGaugeDetector.Direction direction, int trackWidth,
                                    int trackHeight, int centerYOffset) {
            return createCustom(width, height, leftRatio, current, after, direction,
                    trackWidth, trackHeight, centerYOffset, rgb(61, 75, 50));
        }

        static Fixture createCustom(int width, int height, float leftRatio, int current, int after,
                                    StaminaGaugeDetector.Direction direction, int trackWidth,
                                    int trackHeight, int centerYOffset, int lossColor) {
            StaminaGaugeDetector.Region region = StaminaGaugeDetector.scanRegion(width, height);
            int[] pixels = new int[region.width * region.height];
            for (int index = 0; index < pixels.length; index++) pixels[index] = rgb(22, 24, 31);

            int logicalLeft = Math.round(width * leftRatio);
            int endpointInset = Math.max(1, Math.round(trackHeight * 0.30f));
            int rawLeft = logicalLeft - endpointInset;
            int rawRight = rawLeft + trackWidth;
            double scale = width / 3120.0;
            int centerY = (int) Math.round(100 * scale) + centerYOffset;
            int top = centerY - trackHeight / 2;
            int bottom = top + trackHeight;
            int currentBoundary = logicalLeft + Math.round(trackWidth * current / 100f);
            int afterBoundary = logicalLeft + Math.round(trackWidth * after / 100f);

            for (int y = top; y < bottom; y++) {
                for (int x = rawLeft; x < rawRight; x++) {
                    int color;
                    if (direction == StaminaGaugeDetector.Direction.GAIN) {
                        if (x < currentBoundary) color = normal(x - logicalLeft, trackWidth);
                        else if (x < afterBoundary) color = rgb(150 + Math.max(0, x - logicalLeft) * 80 / trackWidth, 250, 150);
                        else color = rgb(78, 78, 78);
                    } else if (direction == StaminaGaugeDetector.Direction.LOSS) {
                        if (x < afterBoundary) color = normal(x - logicalLeft, trackWidth);
                        else if (x < currentBoundary) color = lossColor;
                        else color = rgb(78, 78, 78);
                    } else {
                        color = x < currentBoundary ? normal(x - logicalLeft, trackWidth) : rgb(78, 78, 78);
                    }
                    set(pixels, region, x, y, color);
                }
            }
            return new Fixture(width, height, region, pixels);
        }

        static Fixture blank(int width, int height) {
            StaminaGaugeDetector.Region region = StaminaGaugeDetector.scanRegion(width, height);
            int[] pixels = new int[region.width * region.height];
            for (int index = 0; index < pixels.length; index++) pixels[index] = rgb(22, 24, 31);
            return new Fixture(width, height, region, pixels);
        }

        void paintGreenScenery(float leftRatio) {
            double scale = width / 3120.0;
            int trackHeight = Math.max(8, (int) Math.round(33 * scale));
            int expectedY = (int) Math.round(100 * scale);
            int left = Math.round(width * leftRatio);
            int length = Math.max(20, Math.round(width * 0.073f));
            int top = expectedY - trackHeight;
            int baseHeight = Math.max(7, Math.round(trackHeight * 0.56f));
            for (int offset = 0; offset < length; offset++) {
                int wobble = ((offset / Math.max(2, trackHeight / 4)) % 5) - 2;
                int columnTop = top + Math.max(0, wobble);
                int columnBottom = top + baseHeight + Math.min(0, wobble);
                for (int y = columnTop; y < columnBottom; y++) {
                    set(pixels, region, left + offset, y,
                            rgb(48 + offset % 18, 126 + offset % 27, 67 + offset % 13));
                }
            }
        }

        void paintLowSaturationBand(float leftRatio) {
            double scale = width / 3120.0;
            int trackWidth = Math.max(72, (int) Math.round(428 * scale));
            int trackHeight = Math.max(8, (int) Math.round(33 * scale));
            int endpointInset = Math.max(1, Math.round(trackHeight * 0.30f));
            int left = Math.round(width * leftRatio) - endpointInset;
            int centerY = (int) Math.round(width * 0.032);
            int top = centerY - trackHeight / 2;
            for (int y = top; y < top + trackHeight; y++) {
                for (int x = left; x < left + trackWidth; x++) {
                    set(pixels, region, x, y, rgb(108, 124, 133));
                }
            }
        }

        void paintPaleYellowGainPreview(float leftRatio, int current, int after) {
            double scale = width / 3120.0;
            int trackWidth = Math.max(72, (int) Math.round(428 * scale));
            int trackHeight = Math.max(8, (int) Math.round(33 * scale));
            int logicalLeft = Math.round(width * leftRatio);
            int currentBoundary = logicalLeft + Math.round(trackWidth * current / 100f);
            int afterBoundary = logicalLeft + Math.round(trackWidth * after / 100f);
            int centerY = (int) Math.round(100 * scale);
            int top = centerY - trackHeight / 2;
            int previewWidth = Math.max(1, afterBoundary - currentBoundary);
            for (int y = top; y < top + trackHeight; y++) {
                for (int x = currentBoundary; x < afterBoundary; x++) {
                    float progress = (x - currentBoundary) / (float) previewWidth;
                    float transition = Math.min(1f, progress / 0.67f);
                    set(pixels, region, x, y, rgb(
                            121 + Math.round(transition * 134),
                            250 + Math.round(transition * 5),
                            150 + Math.round(transition * 23)));
                }
            }
        }

        void paintClippedGaugeBand() {
            int trackHeight = Math.max(8, (int) Math.round(width * 0.0085));
            int trackWidth = (int) Math.round(trackHeight * 12.8);
            int centerY = (int) Math.round(width * 0.032);
            int top = centerY - trackHeight / 2;
            for (int y = top; y < top + trackHeight; y++) {
                for (int x = region.left; x < region.left + trackWidth; x++) {
                    set(pixels, region, x, y, normal(x - region.left, trackWidth));
                }
            }
        }

        void paintThinSameRowDecoy() {
            int trackHeight = Math.max(6, (int) Math.round(width * 0.0034));
            int trackWidth = (int) Math.round(trackHeight * 11.8);
            int left = Math.round(width * 0.575f);
            int colorEnd = left + Math.max(8, (int) Math.round(trackHeight * 1.5));
            int centerY = (int) Math.round(width * 0.032);
            int top = centerY - trackHeight / 2;
            for (int y = top; y < top + trackHeight; y++) {
                for (int x = left; x < left + trackWidth; x++) {
                    int color = x < colorEnd
                            ? rgb(35, 202, 148)
                            : rgb(61, 75, 50);
                    set(pixels, region, x, y, color);
                }
            }
        }

        void paintUpperBannerDecoy() {
            int trackHeight = Math.max(8, (int) Math.round(width * 0.0085));
            int trackWidth = (int) Math.round(trackHeight * 12.8);
            int endpointInset = Math.max(1, Math.round(trackHeight * 0.30f));
            int rawLeft = region.left + Math.max(24, trackHeight * 3);
            int logicalLeft = rawLeft + endpointInset;
            int filledEnd = logicalLeft + Math.round(trackWidth * 0.38f);
            int centerY = (int) Math.round(width * 0.015);
            int top = centerY - trackHeight / 2;
            for (int y = top; y < top + trackHeight; y++) {
                for (int x = rawLeft; x < rawLeft + trackWidth; x++) {
                    int color = x < filledEnd
                            ? normal(x - logicalLeft, trackWidth)
                            : rgb(78, 78, 78);
                    set(pixels, region, x, y, color);
                }
            }
        }

        StaminaGaugeDetector.Result detect(StaminaGaugeDetector.Anchor anchor) {
            return StaminaGaugeDetector.detect(width, height, region, pixels, anchor);
        }

        private static int normal(int offset, int width) {
            float progress = Math.max(0, offset) / (float) Math.max(1, width - 1);
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
