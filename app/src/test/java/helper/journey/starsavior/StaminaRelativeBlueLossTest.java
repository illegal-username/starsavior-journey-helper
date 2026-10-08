package helper.journey.starsavior;

import static org.junit.Assert.*;
import org.junit.Test;

/** Generated materials only: relative evidence must survive color and size changes. */
public final class StaminaRelativeBlueLossTest {
    @Test public void readsBlueLossAgainstVisibleNeutralTailAcrossValuesAndBrightness() {
        for (int width : new int[] {1920, 2340, 3120}) {
            for (int current : new int[] {13, 37, 62, 84}) {
                for (int offset : new int[] {0, 7, 15}) {
                    StaminaGaugeDetectorTest.Fixture fixture = StaminaGaugeDetectorTest.Fixture.createWithLossColor(
                            width, width / 2, .375f, current, 0, rgb(40, 55, 61));
                    shiftBrightness(fixture, offset);
                    StaminaGaugeDetector.Result result = fixture.detect(null);
                    String label = width + " " + current + " brightness=" + offset;
                    assertNotNull(label, result);
                    assertEquals(label, StaminaGaugeDetector.Direction.LOSS, result.direction);
                    assertTrue(label + " current=" + result.current, Math.abs(current - result.current) <= 1);
                    assertEquals(label, 0, result.after);
                }
            }
        }
    }

    @Test public void keepsBlueTintedTailNeutralWithoutASeparateLossPlateau() {
        for (int current : new int[] {0, 43, 87}) {
            StaminaGaugeDetectorTest.Fixture fixture = StaminaGaugeDetectorTest.Fixture.create(
                    2340, 1080, .375f, current, current, StaminaGaugeDetector.Direction.NONE);
            replaceColor(fixture, rgb(78,78,78), rgb(82,96,99));
            StaminaGaugeDetector.Result result = fixture.detect(null);
            assertNotNull(result);
            assertEquals(StaminaGaugeDetector.Direction.NONE, result.direction);
            assertTrue(Math.abs(current - result.current) <= 1);
            assertEquals(result.current, result.after);
        }
    }

    @Test public void doesNotCallAchromaticDarkeningALossPreview() {
        for (int darkWidth : new int[] {17, 42, 73}) {
            StaminaGaugeDetectorTest.Fixture fixture = StaminaGaugeDetectorTest.Fixture.createWithLossColor(
                    2340, 1080, .375f, darkWidth, 0, rgb(42, 48, 55));
            StaminaGaugeDetector.Result result = fixture.detect(null);
            assertNotNull(result);
            assertEquals(StaminaGaugeDetector.Direction.NONE, result.direction);
            assertEquals(0, result.current);
            assertEquals(0, result.after);
        }
    }

    private static void shiftBrightness(StaminaGaugeDetectorTest.Fixture fixture, int offset) {
        for (int i = 0; i < fixture.pixels.length; i++) {
            int color = fixture.pixels[i];
            fixture.pixels[i] = rgb(Math.min(255, (color >>> 16 & 255) + offset),
                    Math.min(255, (color >>> 8 & 255) + offset), Math.min(255, (color & 255) + offset));
        }
    }
    private static void replaceColor(StaminaGaugeDetectorTest.Fixture fixture, int from, int to) {
        for (int i = 0; i < fixture.pixels.length; i++) if (fixture.pixels[i] == from) fixture.pixels[i] = to;
    }
    private static int rgb(int r, int g, int b) { return 0xff000000 | r << 16 | g << 8 | b; }
}
