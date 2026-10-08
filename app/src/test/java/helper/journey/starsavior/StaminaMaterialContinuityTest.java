package helper.journey.starsavior;

import static org.junit.Assert.*;
import org.junit.Test;

/** Synthetic translucent backgrounds and highlight palettes, without game assets. */
public final class StaminaMaterialContinuityTest {
    @Test public void followsAnInteriorLossPlateauEvenWhenTheOutsideHasTheSameColor() {
        for (int width : new int[] {1280, 1920, 3120}) {
            for (int[] values : new int[][] {{84, 69}, {62, 47}, {35, 20}}) {
                for (int shift : new int[] {0, width / 120}) {
                    int trackWidth = Math.round(width * 428f / 3120);
                    int trackHeight = Math.round(width * 33f / 3120);
                    StaminaGaugeDetectorTest.Fixture f = StaminaGaugeDetectorTest.Fixture.createCustom(
                            width, width * 9 / 16, .36f, values[0], values[1],
                            StaminaGaugeDetector.Direction.LOSS, trackWidth, trackHeight, shift);
                    int left = Math.round(width * .36f);
                    int top = Math.round(width * 100f / 3120) + shift - trackHeight / 2;
                    int start = left + Math.round(trackWidth * values[1] / 100f);
                    int end = left + Math.round(trackWidth * values[0] / 100f);
                    for (int y = Math.max(0, top - trackHeight); y < Math.min(f.region.height, top + 2 * trackHeight); y++) {
                        if (y >= top && y < top + trackHeight) continue;
                        for (int x = start; x < end; x++) f.pixels[y * f.region.width + x - f.region.left] = rgb(61, 75, 50);
                    }
                    check(f, values[0], values[1], StaminaGaugeDetector.Direction.LOSS);
                }
            }
        }
    }

    @Test public void keepsWarmNeutralTailSeparateFromLossAtDifferentSizesAndPositions() {
        for (int width : new int[] {1280, 2340, 3120}) {
            for (int current : new int[] {23, 57, 88}) {
                StaminaGaugeDetectorTest.Fixture f = StaminaGaugeDetectorTest.Fixture.create(
                        width, width / 2, .375f, current, current - 13,
                        StaminaGaugeDetector.Direction.LOSS, width / 160);
                replace(f, rgb(78, 78, 78), rgb(99, 99, 86));
                check(f, current, current - 13, StaminaGaugeDetector.Direction.LOSS);
            }
        }
    }

    @Test public void recognizesCyanAndYellowRecoveryHighlightsAcrossScreenShapes() {
        int[][] palettes = {{80, 208, 194}, {94, 226, 215}, {160, 248, 142}};
        for (int[] screen : new int[][] {{1280, 1153}, {1920, 1080}, {3120, 1440}}) {
            for (int after : new int[] {19, 43, 72}) {
                for (int[] palette : palettes) {
                    StaminaGaugeDetectorTest.Fixture f = StaminaGaugeDetectorTest.Fixture.create(
                            screen[0], screen[1], .36f, 0, after, StaminaGaugeDetector.Direction.GAIN);
                    for (int i = 0; i < f.pixels.length; i++) {
                        int c = f.pixels[i];
                        if ((c >>> 8 & 255) == 250 && (c & 255) == 150) f.pixels[i] = rgb(palette[0], palette[1], palette[2]);
                    }
                    check(f, 0, after, StaminaGaugeDetector.Direction.GAIN);
                }
            }
        }
    }

    @Test public void saturatedCurrentFillDoesNotBecomeRecovery() {
        for (int[] palette : new int[][] {{20, 155, 140}, {58, 214, 141}, {85, 218, 65}}) {
            for (int current : new int[] {23, 57, 88}) {
                StaminaGaugeDetectorTest.Fixture f = StaminaGaugeDetectorTest.Fixture.create(
                        2340, 1080, .375f, current, current, StaminaGaugeDetector.Direction.NONE);
                for (int i = 0; i < f.pixels.length; i++) {
                    int c = f.pixels[i];
                    if ((c >>> 8 & 255) > 120) f.pixels[i] = rgb(palette[0], palette[1], palette[2]);
                }
                check(f, current, current, StaminaGaugeDetector.Direction.NONE);
            }
        }
    }

    @Test public void followsTheNeutralOutlineEndBeforeAWeakerAdjacentSceneStrip() {
        for (int width : new int[] {1400, 2100, 2800}) {
            for (int current : new int[] {47, 73}) {
                int trackWidth = Math.round(width * 428f / 3120);
                int trackHeight = Math.round(width * 33f / 3120);
                StaminaGaugeDetectorTest.Fixture f = StaminaGaugeDetectorTest.Fixture.createCustom(
                        width, width / 2, .36f, current, current,
                        StaminaGaugeDetector.Direction.NONE, trackWidth, trackHeight, width / 180);
                int right = Math.round(width * .36f) + trackWidth;
                int top = Math.round(width * 100f / 3120) + width / 180 - trackHeight / 2;
                for (int y = top; y < top + trackHeight; y++) {
                    for (int x = right; x < right + Math.round(trackHeight * .65f); x++) {
                        f.pixels[y * f.region.width + x - f.region.left] = rgb(37, 40, 45);
                    }
                }
                check(f, current, current, StaminaGaugeDetector.Direction.NONE);
            }
        }
    }

    @Test public void retainsYellowGreenLossAtTheFullEndpointIncludingShortPreviews() {
        for (int after : new int[] {79, 87, 98}) {
            StaminaGaugeDetectorTest.Fixture f = StaminaGaugeDetectorTest.Fixture.createWithLossColor(
                    2340, 1080, .375f, 100, after, rgb(76, 78, 53));
            check(f, 100, after, StaminaGaugeDetector.Direction.LOSS);
        }
    }

    @Test public void retainsSubtleCyanLossWithColumnColorNoise() {
        for (int width : new int[] {1280, 2340}) {
            for (int current : new int[] {13, 31, 58}) {
                int loss = rgb(50, 61, 62);
                StaminaGaugeDetectorTest.Fixture f = StaminaGaugeDetectorTest.Fixture.createWithLossColor(
                        width, width / 2, .375f, current, 0, loss);
                for (int i = 0; i < f.pixels.length; i++) {
                    if (f.pixels[i] == loss) {
                        int noise = ((i % f.region.width) % 3 - 1) * 3;
                        f.pixels[i] = rgb(50, 61 + noise, 62);
                    }
                }
                check(f, current, 0, StaminaGaugeDetector.Direction.LOSS);
            }
        }
    }

    private static void replace(StaminaGaugeDetectorTest.Fixture f, int from, int to) {
        for (int i = 0; i < f.pixels.length; i++) if (f.pixels[i] == from) f.pixels[i] = to;
    }

    private static void check(StaminaGaugeDetectorTest.Fixture f, int current, int after,
                              StaminaGaugeDetector.Direction direction) {
        StaminaGaugeDetector.Result result = f.detect(null);
        String label = f.width + "x" + f.height + " expected=" + current + ">" + after;
        assertNotNull(label, result);
        assertEquals(label, direction, result.direction);
        assertTrue(label + " current=" + result.current, Math.abs(result.current - current) <= 1);
        assertTrue(label + " after=" + result.after, Math.abs(result.after - after) <= 1);
        assertTrue(label + " delta", Math.abs((result.after - result.current) - (after - current)) <= 1);
    }

    private static int rgb(int r, int g, int b) { return 0xff000000 | r << 16 | g << 8 | b; }
}
