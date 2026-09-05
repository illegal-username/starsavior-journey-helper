package helper.journey.starsavior;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ScreenCapturePermissionDecisionTest {
    @Test
    public void canceledShareNeverStartsTheCaptureService() {
        assertEquals(
                ScreenCapturePermissionDecision.Action.STAY_IDLE,
                ScreenCapturePermissionDecision.fromResult(false, false));
        assertEquals(
                ScreenCapturePermissionDecision.Action.STAY_IDLE,
                ScreenCapturePermissionDecision.fromResult(true, false));
    }

    @Test
    public void acceptedShareWithTokenStartsTheCaptureService() {
        assertEquals(
                ScreenCapturePermissionDecision.Action.START_CAPTURE_SERVICE,
                ScreenCapturePermissionDecision.fromResult(true, true));
    }
}
