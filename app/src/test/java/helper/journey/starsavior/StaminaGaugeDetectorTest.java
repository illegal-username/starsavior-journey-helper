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
    public void readsFullLossAcrossHudScalesWithoutPixelSpecificCorrections() {
        int[][] screens = {
                {1920, 1080}, {2340, 1080}, {3120, 1440}, {3840, 1772}
        };
        Integer firstAfter = null;
        for (int[] screen : screens) {
            Fixture fixture = Fixture.create(screen[0], screen[1], 0.385f, 100, 85,
                    StaminaGaugeDetector.Direction.LOSS);
            fixture.paintTrailingNeutralHudPatch(0.385f);

            StaminaGaugeDetector.Result result = fixture.detect(null);

            assertNotNull("screen=" + screen[0] + "x" + screen[1], result);
            assertEquals(StaminaGaugeDetector.Direction.LOSS, result.direction);
            assertEquals(100, result.current);
            assertNear(85, result.after, 1);
            if (firstAfter == null) firstAfter = result.after;
            else assertNear(firstAfter, result.after, 1);
        }
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
    public void keepsFullGaugeWhenShortNeutralHudPatchFollowsTrack() {
        Fixture fixture = Fixture.create(3120, 1440, 0.375f, 100, 100,
                StaminaGaugeDetector.Direction.NONE);
        fixture.paintTrailingNeutralHudPatch(0.375f);

        StaminaGaugeDetector.Result result = fixture.detect(null);

        assertNotNull(result);
        assertEquals(StaminaGaugeDetector.Direction.NONE, result.direction);
        assertEquals(100, result.current);
    }

    @Test
    public void ignoresDetachedConditionBadgeAcrossHudScales() {
        int[][] screens = {{2340, 1080}, {3120, 1440}, {3840, 1772}};
        for (int[] screen : screens) {
            Fixture fixture = Fixture.create(screen[0], screen[1], 0.375f, 79, 100,
                    StaminaGaugeDetector.Direction.GAIN);
            fixture.paintDetachedConditionBadge(0.375f);

            StaminaGaugeDetector.Result result = fixture.detect(null);

            assertNotNull(result);
            assertEquals(StaminaGaugeDetector.Direction.GAIN, result.direction);
            assertNear(79, result.current, 1);
            assertEquals(100, result.after);
        }
    }

    @Test
    public void distinguishesIconOccludedFiveFromEmptyAcrossHudScales() {
        int[] widths = {2400, 3120, 3840};
        int[] heights = {1080, 1440, 1772};
        for (int index = 0; index < widths.length; index++) {
            Fixture empty = Fixture.createIconOccludedLowGauge(
                    widths[index], heights[index], 0.375f, 0);
            Fixture five = Fixture.createIconOccludedLowGauge(
                    widths[index], heights[index], 0.375f, 5);
            empty.paintLowerGaugeDecoy();
            five.paintLowerGaugeDecoy();

            StaminaGaugeDetector.Result emptyResult = empty.detect(null);
            StaminaGaugeDetector.Result fiveResult = five.detect(null);

            assertNotNull(emptyResult);
            assertNotNull(fiveResult);
            assertEquals(StaminaGaugeDetector.Direction.NONE, emptyResult.direction);
            assertEquals(StaminaGaugeDetector.Direction.NONE, fiveResult.direction);
            assertEquals(0, emptyResult.current);
            assertNear(5, fiveResult.current, 2);
            assertTrue("detector selected the lower green decoy",
                    fiveResult.anchor.centerYRatio < 0.050f);
        }
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
            assertNear(33, result.current, 2);
            assertNear(64, result.after, 2);
        }
    }

    @Test
    public void derivesScaleFromVerticallyVaryingColorRows() {
        int[][] screens = {
                {1920, 1080}, {2340, 1080}, {3120, 1440}, {3840, 1772}
        };
        for (int[] screen : screens) {
            Fixture fixture = Fixture.create(screen[0], screen[1], 0.385f,
                    17, 50, StaminaGaugeDetector.Direction.GAIN);
            fixture.shortenColoredEdgeRows(0.385f, 50);

            StaminaGaugeDetector.Result result = fixture.detect(null);

            assertNotNull("screen=" + screen[0] + "x" + screen[1], result);
            assertEquals(StaminaGaugeDetector.Direction.GAIN, result.direction);
            assertNear(17, result.current, 2);
            assertNear(50, result.after, 2);
        }
    }

    @Test
    public void readsObservedEndpointsWhenIconCoversCenterRowsAcrossHudScales() {
        int[][] screens = {
                {1920, 1080}, {2340, 1080}, {3120, 1440}, {3840, 1772}
        };
        for (int[] screen : screens) {
            Fixture fixture = Fixture.createCenterOccludedSteadyGauge(
                    screen[0], screen[1], 0.375f, 70);

            StaminaGaugeDetector.Result result = fixture.detect(null);

            assertNotNull(result);
            assertEquals(StaminaGaugeDetector.Direction.NONE, result.direction);
            assertNear(70, result.current, 1);
        }
    }

    @Test
    public void dividesOnlyTheObservedZeroToHundredSpanAcrossHudScales() {
        int[] values = {0, 5, 24, 32, 70, 95, 100};
        int[][] screens = {
                {1920, 1080}, {2340, 1080}, {3120, 1440}, {3840, 1772}
        };
        for (int[] screen : screens) {
            for (int value : values) {
                Fixture fixture = Fixture.create(screen[0], screen[1], 0.375f,
                        value, value, StaminaGaugeDetector.Direction.NONE);

                StaminaGaugeDetector.Result result = fixture.detect(null);

                assertNotNull(result);
                assertEquals(StaminaGaugeDetector.Direction.NONE, result.direction);
                assertNear(value, result.current, 1);
            }
        }
    }

    @Test
    public void readsIconOccludedLowSteadyLossAndGainAcrossHudScales() {
        int[][] screens = {
                {1920, 1080}, {2340, 1080}, {3120, 1440}, {3840, 1772}
        };
        for (int[] screen : screens) {
            Fixture steady = Fixture.create(screen[0], screen[1], 0.375f,
                    15, 15, StaminaGaugeDetector.Direction.NONE);
            Fixture loss = Fixture.create(screen[0], screen[1], 0.375f,
                    15, 0, StaminaGaugeDetector.Direction.LOSS);
            Fixture gain = Fixture.create(screen[0], screen[1], 0.375f,
                    15, 18, StaminaGaugeDetector.Direction.GAIN);
            steady.paintTrackHeadOcclusion(0.375f);
            loss.paintTrackHeadOcclusion(0.375f);
            gain.paintTrackHeadOcclusion(0.375f);

            StaminaGaugeDetector.Result steadyResult = steady.detect(null);
            StaminaGaugeDetector.Result lossResult = loss.detect(null);
            StaminaGaugeDetector.Result gainResult = gain.detect(null);

            assertNotNull("steady " + screen[0], steadyResult);
            assertNotNull("loss " + screen[0], lossResult);
            assertNotNull("gain " + screen[0], gainResult);
            assertEquals("steady " + screen[0], StaminaGaugeDetector.Direction.NONE,
                    steadyResult.direction);
            assertEquals("loss " + screen[0], StaminaGaugeDetector.Direction.LOSS,
                    lossResult.direction);
            assertEquals("gain " + screen[0], StaminaGaugeDetector.Direction.GAIN,
                    gainResult.direction);
            assertNear(15, steadyResult.current, 1);
            assertNear(15, lossResult.current, 1);
            assertEquals(0, lossResult.after);
            assertNear(15, gainResult.current, 1);
            assertNear(18, gainResult.after, 1);
        }
    }

    @Test
    public void followsLossThroughNeutralColoredGradientToObservedTrackEnd() {
        int[][] screens = {
                {1920, 1080}, {2340, 1080}, {3120, 1440}, {3840, 1772}
        };
        for (int[] screen : screens) {
            Fixture fixture = Fixture.create(screen[0], screen[1], 0.375f,
                    70, 55, StaminaGaugeDetector.Direction.LOSS);
            fixture.paintNeutralOverlappingLossStart(0.375f, 55, 70);
            fixture.paintTrailingNeutralHudPatch(0.375f);

            StaminaGaugeDetector.Result result = fixture.detect(null);

            assertNotNull("screen=" + screen[0] + "x" + screen[1], result);
            assertEquals(StaminaGaugeDetector.Direction.LOSS, result.direction);
            assertNear(70, result.current, 1);
            assertNear(55, result.after, 1);
        }
    }

    @Test
    public void previousAnchorCannotReplaceCurrentFrameEndpoints() {
        int width = 3120;
        float leftRatio = 0.375f;
        Fixture fixture = Fixture.create(width, 1440, leftRatio, 70, 70,
                StaminaGaugeDetector.Direction.NONE);
        StaminaGaugeDetector.Anchor nearbyButWrong = new StaminaGaugeDetector.Anchor(
                leftRatio - 10f / width, 100f / width);

        StaminaGaugeDetector.Result result = fixture.detect(nearbyButWrong);

        assertNotNull(result);
        assertNear(70, result.current, 1);
        assertNear(Math.round(width * leftRatio), Math.round(result.anchor.leftRatio * width), 1);
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

            int trackLeft = Math.round(width * leftRatio);
            int trackRight = trackLeft + trackWidth;
            double scale = width / 3120.0;
            int centerY = (int) Math.round(100 * scale) + centerYOffset;
            int top = centerY - trackHeight / 2;
            int bottom = top + trackHeight;
            int currentBoundary = trackLeft + Math.round(trackWidth * current / 100f);
            int afterBoundary = trackLeft + Math.round(trackWidth * after / 100f);

            for (int y = top; y < bottom; y++) {
                for (int x = trackLeft; x < trackRight; x++) {
                    int color;
                    if (direction == StaminaGaugeDetector.Direction.GAIN) {
                        if (x < currentBoundary) color = normal(x - trackLeft, trackWidth);
                        else if (x < afterBoundary) color = rgb(150 + Math.max(0, x - trackLeft) * 80 / trackWidth, 250, 150);
                        else color = rgb(78, 78, 78);
                    } else if (direction == StaminaGaugeDetector.Direction.LOSS) {
                        if (x < afterBoundary) color = normal(x - trackLeft, trackWidth);
                        else if (x < currentBoundary) color = lossColor;
                        else color = rgb(78, 78, 78);
                    } else {
                        color = current > 0 && x < currentBoundary
                                ? normal(x - trackLeft, trackWidth) : rgb(78, 78, 78);
                    }
                    set(pixels, region, x, y, color);
                }
            }
            return new Fixture(width, height, region, pixels);
        }

        static Fixture createIconOccludedLowGauge(int width, int height, float leftRatio,
                                                  int current) {
            if (current != 0 && current != 5) {
                throw new IllegalArgumentException("fixture supports only the endpoint cases");
            }
            Fixture fixture = blank(width, height);
            double scale = width / 3120.0;
            int trackWidth = Math.max(72, (int) Math.round(428 * scale));
            int trackHeight = Math.max(8, (int) Math.round(33 * scale));
            int logicalLeft = Math.round(width * leftRatio);
            int logicalRight = logicalLeft + trackWidth;
            int centerY = (int) Math.round(100 * scale);
            int top = centerY - trackHeight / 2;
            int boundary = logicalLeft + Math.round(trackWidth * current / 100f);
            int iconOcclusion = Math.max(2, Math.round(trackHeight * 0.48f));
            int edgeRows = Math.max(1, Math.round(trackHeight * 0.18f));
            for (int y = top; y < top + trackHeight; y++) {
                boolean iconCoversCenter = y >= top + edgeRows
                        && y < top + trackHeight - edgeRows;
                for (int x = logicalLeft; x < logicalRight; x++) {
                    int color = current > 0 && x < boundary
                            ? normal(x - logicalLeft, trackWidth) : rgb(78, 78, 78);
                    if (iconCoversCenter && x < logicalLeft + iconOcclusion) {
                        color = rgb(42, 34, 31);
                    }
                    set(fixture.pixels, fixture.region, x, y, color);
                }
            }
            return fixture;
        }

        static Fixture createCenterOccludedSteadyGauge(int width, int height, float leftRatio,
                                                       int current) {
            Fixture fixture = blank(width, height);
            double scale = width / 3120.0;
            int trackWidth = Math.max(72, (int) Math.round(428 * scale));
            int trackHeight = Math.max(8, (int) Math.round(33 * scale));
            int trackLeft = Math.round(width * leftRatio);
            int trackRight = trackLeft + trackWidth;
            int boundary = trackLeft + Math.round(trackWidth * current / 100f);
            int centerY = (int) Math.round(100 * scale);
            int top = centerY - trackHeight / 2;
            int edgeRows = Math.max(1, Math.round(trackHeight * 0.18f));

            for (int y = top; y < top + trackHeight; y++) {
                boolean exposedEdge = y < top + edgeRows
                        || y >= top + trackHeight - edgeRows;
                for (int x = trackLeft; x < trackRight; x++) {
                    int color = x < boundary
                            ? normal(x - trackLeft, trackWidth) : rgb(78, 78, 78);
                    if (!exposedEdge && x < trackLeft + Math.max(2,
                            Math.round(trackHeight * 0.30f))) {
                        color = rgb(42, 34, 31);
                    }
                    set(fixture.pixels, fixture.region, x, y, color);
                }
            }

            int iconWidth = Math.max(4, Math.round(trackHeight * 0.80f));
            int iconHeight = Math.max(4, Math.round(trackHeight * 0.80f));
            int iconRight = trackLeft - 1;
            int iconLeft = iconRight - iconWidth;
            int iconTop = centerY - iconHeight / 2;
            for (int y = iconTop; y < iconTop + iconHeight; y++) {
                for (int x = iconLeft; x < iconRight; x++) {
                    set(fixture.pixels, fixture.region, x, y, rgb(235, 224, 190));
                }
            }
            return fixture;
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

        void paintTrailingNeutralHudPatch(float leftRatio) {
            double scale = width / 3120.0;
            int trackWidth = Math.max(72, (int) Math.round(428 * scale));
            int trackHeight = Math.max(8, (int) Math.round(33 * scale));
            int trackLeft = Math.round(width * leftRatio);
            int trackRight = trackLeft + trackWidth;
            int centerY = (int) Math.round(100 * scale);
            int top = centerY - trackHeight / 2;
            int patchWidth = Math.max(3, Math.round(trackHeight * 0.60f));
            int patchTop = Math.max(region.top, top - trackHeight);
            int patchBottom = Math.min(region.top + region.height,
                    top + trackHeight * 2);
            for (int y = patchTop; y < patchBottom; y++) {
                for (int x = trackRight; x < trackRight + patchWidth; x++) {
                    set(pixels, region, x, y, rgb(78, 78, 78));
                }
            }
        }

        void paintTrackHeadOcclusion(float leftRatio) {
            double scale = width / 3120.0;
            int trackHeight = Math.max(8, (int) Math.round(33 * scale));
            int logicalLeft = Math.round(width * leftRatio);
            int centerY = (int) Math.round(100 * scale);
            int top = centerY - trackHeight / 2;
            int edgeRows = Math.max(1, Math.round(trackHeight * 0.18f));
            int occludedEnd = logicalLeft + Math.max(2,
                    Math.round(trackHeight * 0.55f));
            for (int y = top + edgeRows; y < top + trackHeight - edgeRows; y++) {
                for (int x = logicalLeft; x < occludedEnd; x++) {
                    set(pixels, region, x, y, rgb(42, 34, 31));
                }
            }
        }

        void paintNeutralOverlappingLossStart(float leftRatio, int after, int current) {
            double scale = width / 3120.0;
            int trackWidth = Math.max(72, (int) Math.round(428 * scale));
            int trackHeight = Math.max(8, (int) Math.round(33 * scale));
            int logicalLeft = Math.round(width * leftRatio);
            int afterBoundary = logicalLeft + Math.round(trackWidth * after / 100f);
            int currentBoundary = logicalLeft + Math.round(trackWidth * current / 100f);
            int overlapEnd = afterBoundary + (currentBoundary - afterBoundary) / 2;
            int centerY = (int) Math.round(100 * scale);
            int top = centerY - trackHeight / 2;
            for (int y = top; y < top + trackHeight; y++) {
                for (int x = afterBoundary; x < overlapEnd; x++) {
                    // This olive-gray is intentionally accepted by both the loss
                    // and neutral masks, like the translucent in-game gradient.
                    set(pixels, region, x, y, rgb(61, 75, 65));
                }
            }
        }

        void paintDetachedConditionBadge(float leftRatio) {
            double scale = width / 3120.0;
            int trackWidth = Math.max(72, (int) Math.round(428 * scale));
            int trackHeight = Math.max(8, (int) Math.round(33 * scale));
            int trackLeft = Math.round(width * leftRatio);
            int trackRight = trackLeft + trackWidth;
            int centerY = (int) Math.round(100 * scale);
            int top = centerY - trackHeight / 2;
            int gap = Math.round(trackHeight * 2.5f);
            int badgeWidth = Math.max(1, Math.round(trackHeight * 0.70f));
            for (int y = top; y < top + trackHeight; y++) {
                for (int x = trackRight + gap; x < trackRight + gap + badgeWidth; x++) {
                    set(pixels, region, x, y, rgb(78, 78, 78));
                }
            }
        }

        void shortenColoredEdgeRows(float leftRatio, int after) {
            double scale = width / 3120.0;
            int trackWidth = Math.max(72, (int) Math.round(428 * scale));
            int trackHeight = Math.max(8, (int) Math.round(33 * scale));
            int logicalLeft = Math.round(width * leftRatio);
            int afterBoundary = logicalLeft + Math.round(trackWidth * after / 100f);
            int shortenedEnd = afterBoundary - Math.max(1, Math.round(trackWidth * 0.10f));
            int centerY = (int) Math.round(100 * scale);
            int top = centerY - trackHeight / 2;
            int edgeRows = Math.max(1, Math.round(trackHeight * 0.20f));
            for (int y = top; y < top + trackHeight; y++) {
                boolean edge = y < top + edgeRows || y >= top + trackHeight - edgeRows;
                if (!edge) continue;
                for (int x = shortenedEnd; x < afterBoundary; x++) {
                    set(pixels, region, x, y, rgb(78, 78, 78));
                }
            }
        }

        void paintLowerGaugeDecoy() {
            double scale = width / 3120.0;
            int trackWidth = Math.max(72, (int) Math.round(428 * scale));
            int trackHeight = Math.max(8, (int) Math.round(33 * scale));
            int left = Math.round(width * 0.382f);
            int colorEnd = left + Math.round(trackWidth * 0.72f);
            int centerY = (int) Math.round(width * 0.052f);
            int top = centerY - trackHeight / 2;
            for (int y = top; y < top + trackHeight; y++) {
                for (int x = left; x < left + trackWidth; x++) {
                    int color = x < colorEnd
                            ? normal(x - left, trackWidth)
                            : rgb(78, 78, 78);
                    set(pixels, region, x, y, color);
                }
            }
        }

        void paintLowSaturationBand(float leftRatio) {
            double scale = width / 3120.0;
            int trackWidth = Math.max(72, (int) Math.round(428 * scale));
            int trackHeight = Math.max(8, (int) Math.round(33 * scale));
            int left = Math.round(width * leftRatio);
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
            double scale = width / 3120.0;
            int trackHeight = Math.max(8, (int) Math.round(33 * scale));
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
            double scale = width / 3120.0;
            int trackHeight = Math.max(8, (int) Math.round(33 * scale));
            int trackWidth = (int) Math.round(trackHeight * 12.8);
            int trackLeft = region.left + Math.max(24, trackHeight * 3);
            int filledEnd = trackLeft + Math.round(trackWidth * 0.38f);
            int centerY = (int) Math.round(width * 0.015);
            int top = centerY - trackHeight / 2;
            for (int y = top; y < top + trackHeight; y++) {
                for (int x = trackLeft; x < trackLeft + trackWidth; x++) {
                    int color = x < filledEnd
                            ? normal(x - trackLeft, trackWidth)
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
