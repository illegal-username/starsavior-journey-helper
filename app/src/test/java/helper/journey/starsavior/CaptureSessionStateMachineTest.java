package helper.journey.starsavior;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CaptureSessionStateMachineTest {
    @Test
    public void rotationInterruptsOnlyTheInFlightFrameAndKeepsProjectionActive() {
        CaptureSessionStateMachine state = new CaptureSessionStateMachine();
        state.activate();
        assertTrue(state.beginCapture());
        int generation = state.generation();

        assertTrue(state.interruptForResize());

        assertEquals(CaptureSessionStateMachine.Phase.ACTIVE, state.phase());
        assertFalse(state.isActive(generation));
        assertTrue(state.isActive(state.generation()));
        assertTrue(state.beginCapture());
        assertFalse(state.finishCapture(generation));
        assertTrue(state.isCapturing());
    }

    @Test
    public void canceledOrStoppedSharingInvalidatesOutstandingCallbacks() {
        CaptureSessionStateMachine state = new CaptureSessionStateMachine();
        state.activate();
        assertTrue(state.beginCapture());
        int oldGeneration = state.generation();

        state.waitForPermission();

        assertEquals(CaptureSessionStateMachine.Phase.WAITING_FOR_PERMISSION, state.phase());
        assertFalse(state.isActive(oldGeneration));
        assertFalse(state.beginCapture());
    }
}
