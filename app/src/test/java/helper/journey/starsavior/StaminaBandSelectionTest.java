package helper.journey.starsavior;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import org.junit.Test;

/** A material's horizontal shading edge must not replace the gauge's visible outer borders. */
public final class StaminaBandSelectionTest {
    @Test public void keepsNeutralTailBelowShadedFill() {
        assertAcrossSizes(85, 85);
    }

    @Test public void keepsGainEdgesBelowShadedFill() {
        assertAcrossSizes(31, 87);
    }

    @Test public void keepsLossTailBelowShadedFill() {
        assertAcrossSizes(100, 85);
    }

    private static void assertAcrossSizes(int current, int after) {
        for (int width : new int[]{1920, 2560, 3840}) {
            for (int height : new int[]{24, 32}) {
                // Shading changes material appearance, never the gauge's endpoints.
                assertReading(new Scene(width, height, current, after, false), current, after);
                assertReading(new Scene(width, height, current, after, true), current, after);
            }
        }
    }

    private static void assertReading(Scene scene, int current, int after) {
        StaminaGaugeDetector.Result result = scene.detect();
        String label = "screen=" + scene.width + ", HUD=" + scene.h + ", shaded=" + scene.shaded
                + ", " + current + "->" + after;
        assertNotNull(label, result);
        assertTrue(label + ", current=" + result.current, Math.abs(result.current - current) <= 1);
        assertTrue(label + ", after=" + result.after, Math.abs(result.after - after) <= 1);
        assertEquals(label, after > current ? StaminaGaugeDetector.Direction.GAIN
                : after < current ? StaminaGaugeDetector.Direction.LOSS
                : StaminaGaugeDetector.Direction.NONE, result.direction);
        double observedCenter = scene.top + (scene.h - 1) * .5;
        assertTrue(label + ", wrong vertical borders", Math.abs(result.anchor.centerYRatio
                * scene.width - observedCenter) <= .55);
    }

    static final class Scene {
        final int width, h, top, left, span;
        final boolean shaded;
        final StaminaGaugeDetector.Region region;
        final int[] pixels;

        Scene(int width, int h, int current, int after, boolean shaded) {
            this.width = width;
            this.h = h;
            this.shaded = shaded;
            top = (int) Math.round(width * .034) - h / 2;
            left = (int) Math.round(width * .35);
            span = 14 * h;
            region = StaminaGaugeDetector.scanRegion(width, width / 2);
            pixels = new int[region.width * region.height];
            Arrays.fill(pixels, rgb(22, 24, 31));
            int normalEnd = left + (int) Math.round(span * Math.min(current, after) / 100.);
            int previewEnd = left + (int) Math.round(span * Math.max(current, after) / 100.);
            int shadedRows = Math.max(1, (int) Math.round(h * .12));
            for (int y = top; y < top + h; y++) {
                for (int x = left; x < left + span; x++) {
                    int[] color = x < normalEnd ? new int[]{60, 185, 111}
                            : x < previewEnd ? (after > current ? new int[]{155, 250, 150}
                            : new int[]{61, 75, 50}) : new int[]{78, 78, 78};
                    // The internal row transition exists only inside the bright material.
                    // The actual neutral/loss tail continues to the same exposed R.
                    if (shaded && y < top + shadedRows
                            && (x < normalEnd || after > current && x < previewEnd)) {
                        for (int channel = 0; channel < 3; channel++) color[channel] -= 35;
                    }
                    pixels[(y - region.top) * region.width + x - region.left]
                            = rgb(color[0], color[1], color[2]);
                }
            }
        }

        StaminaGaugeDetector.Result detect() {
            return StaminaGaugeDetector.detect(width, width / 2, region, pixels, null);
        }
    }

    private static int rgb(int r, int g, int b) { return 0xff000000 | r << 16 | g << 8 | b; }

}
