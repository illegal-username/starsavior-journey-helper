package helper.journey.starsavior;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LegacyCaptureResizePolicyTest {
    @Test
    public void android13RepairsPortraitProjectionBeforeLandscapeCapture() {
        assertTrue(LegacyCaptureResizePolicy.shouldResize(
                33, 1440, 3120, 3120, 1440));
    }

    @Test
    public void android13LeavesSameOrientationAndMinorMetricDifferencesAlone() {
        assertFalse(LegacyCaptureResizePolicy.shouldResize(
                33, 3120, 1440, 3088, 1440));
    }

    @Test
    public void android14AndLaterAlwaysUseCapturedContentResizeCallback() {
        assertFalse(LegacyCaptureResizePolicy.shouldResize(
                34, 1440, 3120, 3120, 1440));
        assertFalse(LegacyCaptureResizePolicy.shouldResize(
                35, 1440, 3120, 3120, 1440));
    }

    @Test
    public void invalidOrSquareMetricsDoNotTriggerLegacyResize() {
        assertFalse(LegacyCaptureResizePolicy.shouldResize(33, 0, 3120, 3120, 1440));
        assertFalse(LegacyCaptureResizePolicy.shouldResize(33, 1800, 1800, 3120, 1440));
    }
}
