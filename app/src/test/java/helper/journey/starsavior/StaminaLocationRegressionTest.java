package helper.journey.starsavior;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class StaminaLocationRegressionTest {
    @Test public void previousLowerDecoyCannotReplaceCurrentEmptyHud() {
        StaminaGaugeDetectorTest.Fixture fixture =
                StaminaGaugeDetectorTest.Fixture.createIconOccludedLowGauge(2400, 1080, .375f, 0);
        fixture.paintLowerGaugeDecoy();
        StaminaGaugeDetector.Result result = fixture.detect(
                new StaminaGaugeDetector.Anchor(.382f, .052f));
        assertNotNull(result);
        assertEquals(0, result.current);
        assertTrue(result.anchor.centerYRatio < .050f);
    }

    @Test public void soleShiftedHudUsesCurrentGeometryDespiteStaleAnchor() {
        StaminaGaugeDetectorTest.Fixture fixture = StaminaGaugeDetectorTest.Fixture.create(
                2400, 1080, .387f, 52, 52, StaminaGaugeDetector.Direction.NONE, 50);
        StaminaGaugeDetector.Result result = fixture.detect(
                new StaminaGaugeDetector.Anchor(.375f, .032f));
        assertNotNull(result);
        assertTrue(Math.abs(result.current - 52) <= 1);
        assertTrue(result.anchor.centerYRatio > .050f);
    }
}
