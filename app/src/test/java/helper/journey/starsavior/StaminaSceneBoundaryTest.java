package helper.journey.starsavior;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import org.junit.Test;

/** A scene contour beside a HUD cannot extend the measured track by itself. */
public final class StaminaSceneBoundaryTest {
    @Test public void doesNotExtendTrackToALowerBackgroundEdge() {
        for (int[] size : new int[][]{{1280, 12}, {1560, 16}, {2560, 24}}) {
            for (int current : new int[]{37, 63, 81}) {
                for (int backgroundVariation : new int[]{32, 48, 64}) {
                    int width = size[0], trackHeight = size[1];
                    StaminaGaugeDetector.Region region = StaminaGaugeDetector.scanRegion(width, width / 2);
                    int[] pixels = new int[region.width * region.height];
                    Arrays.fill(pixels, rgb(22, 24, 31));
                    int left = (int) Math.round(width * .375);
                    int span = trackHeight * 13;
                    int right = left + span;
                    int top = (int) Math.round(width * .032) - trackHeight / 2;
                    int fillEnd = left + (int) Math.round(span * current / 100.0);
                    for (int y = top; y < top + trackHeight; y++) {
                        for (int x = left; x < right; x++) {
                            double u = (x - left) / (double) span;
                            int color = x < fillEnd
                                    ? rgb((int) Math.round(28 + 120 * u),
                                            (int) Math.round(170 + 55 * u),
                                            (int) Math.round(141 - 35 * u))
                                    : rgb(78, 78, 78);
                            set(pixels, region, x, y, color);
                        }
                    }
                    // The actual track ends at `right` in every row. Beyond it,
                    // the upper background continues farther than a lower scene
                    // contour. That lower contour has no matching upper crossing.
                    for (int y = top; y < top + trackHeight; y++) {
                        int end, color;
                        if (y < top + trackHeight * .5) {
                            end = right + trackHeight * 2;
                            color = rgb(45, 49, 58);
                        } else {
                            end = right + (int) Math.round(trackHeight * .33);
                            int shade = (int) Math.round(24 + backgroundVariation
                                    * ((y - top) / (double) trackHeight - .5) * 2);
                            color = rgb(shade, shade + 3, shade + 7);
                        }
                        for (int x = right; x < end; x++) set(pixels, region, x, y, color);
                    }
                    StaminaGaugeDetector.Result result = StaminaGaugeDetector.detect(
                            width, width / 2, region, pixels, null);
                    String label = "screen=" + width + ", HUD=" + trackHeight
                            + ", current=" + current + ", background=" + backgroundVariation;
                    assertNotNull(label, result);
                    assertEquals(label, StaminaGaugeDetector.Direction.NONE, result.direction);
                    assertTrue(label + ", observed=" + result.current,
                            Math.abs(current - result.current) <= 1);
                    assertEquals(label, result.current, result.after);
                }
            }
        }
    }

    private static void set(int[] pixels, StaminaGaugeDetector.Region region, int x, int y, int color) {
        pixels[(y - region.top) * region.width + x - region.left] = color;
    }

    private static int rgb(int r, int g, int b) {
        return 0xff000000 | r << 16 | g << 8 | b;
    }
}
