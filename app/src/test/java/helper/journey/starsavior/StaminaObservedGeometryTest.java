package helper.journey.starsavior;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Continuous synthetic scenes; no screenshot-specific positions or value corrections. */
public final class StaminaObservedGeometryTest {
    @Test public void readsTintedTailAcrossIndependentHudSizes() {
        int[] widths = {1280, 2560, 3840};
        int[] heights = {7, 13, 27};
        for (int width : widths) {
            for (int height : heights) {
                for (int current : new int[]{0, 3, 37, 79, 98, 100}) {
                    Scene scene = new Scene(width, height, current, current, true);
                    assertReading(scene, current, current);
                }
            }
        }
    }

    @Test public void retainsSmallPreviewsAndObservedEndpoints() {
        int[][] states = {{98, 100}, {2, 0}, {70, 67}, {0, 2}, {7, 10}};
        for (int height : new int[]{13, 27}) {
            for (int[] state : states) {
                assertReading(new Scene(1920, height, state[0], state[1], true),
                        state[0], state[1]);
            }
        }
    }

    @Test public void measuresBothEndsInOtherExposedRows() {
        for (int height : new int[]{13, 27}) {
            for (int[] state : new int[][]{{37, 37}, {83, 63}, {31, 68}, {100, 100}, {100, 85}, {70, 100}}) {
                Scene scene = new Scene(2560, height, state[0], state[1], true);
                scene.rectangle(scene.left - height * .4, scene.top + height * .28,
                        scene.left + height * .65, scene.bottom - height * .28, 38, 32, 30);
                scene.rectangle(scene.right - height * .60, scene.top + height * .28,
                        scene.right + height * .4, scene.bottom - height * .28, 38, 32, 30);
                assertReading(scene, state[0], state[1]);
            }
        }
    }

    @Test public void refusesNumbersWhenAnEntireEndpointIsCovered() {
        for (boolean leftHidden : new boolean[]{true, false}) {
            Scene scene = new Scene(1920, 23, 63, 63, true);
            double span = scene.right - scene.left;
            if (leftHidden) {
                scene.rectangle(scene.left - scene.h * 2, scene.top - scene.h * .8,
                        scene.left + span * .12, scene.bottom + scene.h * .8, 37, 35, 43);
            } else {
                scene.rectangle(scene.right - span * .24, scene.top - scene.h * .8,
                        scene.right + scene.h * 3, scene.bottom + scene.h * .8, 37, 35, 43);
            }
            assertNull("A fully hidden endpoint is not a measurable shorter gauge", scene.detect());
        }
    }

    private static void assertReading(Scene scene, int current, int after) {
        String label = "screen=" + scene.width + ", HUD=" + scene.h + ", " + current + "->" + after;
        StaminaGaugeDetector.Result result = scene.detect();
        assertNotNull(label, result);
        assertTrue(label + " current=" + result.current, Math.abs(result.current - current) <= 1);
        assertTrue(label + " after=" + result.after, Math.abs(result.after - after) <= 1);
        assertEquals(label, after > current ? StaminaGaugeDetector.Direction.GAIN
                : after < current ? StaminaGaugeDetector.Direction.LOSS
                : StaminaGaugeDetector.Direction.NONE, result.direction);
        double expectedCenter = (scene.top + scene.bottom) / 2;
        assertTrue(label + " wrong location", Math.abs(result.anchor.centerYRatio * scene.width
                - expectedCenter) <= scene.h * .5);
    }

    private static final class Scene {
        final int width;
        final int screenHeight;
        final double h;
        final double left, right, top, bottom;
        final StaminaGaugeDetector.Region region;
        final int[] pixels;

        Scene(int width, int height, int current, int after, boolean tinted) {
            this.width = width;
            this.screenHeight = width * 3 / 5;
            h = height;
            left = width * .35 + .35;
            right = left + height * 12.4;
            top = width * .03 + .2;
            bottom = top + height;
            region = StaminaGaugeDetector.scanRegion(width, screenHeight);
            pixels = new int[region.width * region.height];
            java.util.Arrays.fill(pixels, rgb(99, 122, 151));
            rectangle(left - h, top - h * .34, right + h * 2.7, bottom + h * .34,
                    20, 27, 37);
            double lo = left + (right - left) * Math.min(current, after) / 100.;
            double hi = left + (right - left) * Math.max(current, after) / 100.;
            for (int y = Math.max(region.top, (int) Math.floor(top));
                    y < Math.min(region.top + region.height, (int) Math.ceil(bottom)); y++) {
                for (int x = Math.max(region.left, (int) Math.floor(left));
                        x < Math.min(region.left + region.width, (int) Math.ceil(right)); x++) {
                    double u = (x + .5 - left) / (right - left);
                    double shade = 2 * (y + .5 - top) / h;
                    double[] color = {20, 27, 37};
                    double active = coverage(x, left, lo) * coverage(y, top, bottom);
                    double preview = coverage(x, lo, hi) * coverage(y, top, bottom);
                    double tail = coverage(x, hi, right) * coverage(y, top, bottom);
                    double[] fill = {25 + 126 * u + shade, 163 + 59 * u + shade,
                            140 - 29 * u + shade};
                    double[] change = after > current
                            ? new double[]{103 + 131 * u + shade, 242 + 9 * u + shade, 159 - 14 * u + shade}
                            : new double[]{48, 65, 45};
                    double[] empty = tinted ? new double[]{69, 77, 91} : new double[]{79, 80, 81};
                    for (int c = 0; c < 3; c++) {
                        color[c] += active * (fill[c] - color[c])
                                + preview * (change[c] - color[c]) + tail * (empty[c] - color[c]);
                    }
                    set(x, y, rgb((int) Math.round(color[0]), (int) Math.round(color[1]),
                            (int) Math.round(color[2])));
                }
            }
        }

        void rectangle(double l, double t, double r, double b, int red, int green, int blue) {
            for (int y = Math.max(region.top, (int) Math.floor(t));
                    y < Math.min(region.top + region.height, (int) Math.ceil(b)); y++) {
                for (int x = Math.max(region.left, (int) Math.floor(l));
                        x < Math.min(region.left + region.width, (int) Math.ceil(r)); x++) {
                    double alpha = coverage(x, l, r) * coverage(y, t, b);
                    int old = pixels[(y - region.top) * region.width + x - region.left];
                    set(x, y, rgb((int) Math.round((old >> 16 & 255) * (1 - alpha) + red * alpha),
                            (int) Math.round((old >> 8 & 255) * (1 - alpha) + green * alpha),
                            (int) Math.round((old & 255) * (1 - alpha) + blue * alpha)));
                }
            }
        }

        private void set(int x, int y, int color) {
            pixels[(y - region.top) * region.width + x - region.left] = color;
        }

        StaminaGaugeDetector.Result detect() {
            return StaminaGaugeDetector.detect(width, screenHeight, region, pixels, null);
        }

        private static double coverage(int pixel, double start, double end) {
            return Math.max(0, Math.min(pixel + 1, end) - Math.max(pixel, start));
        }

        private static int rgb(int r, int g, int b) {
            return 0xff000000 | (r << 16) | (g << 8) | b;
        }
    }
}
