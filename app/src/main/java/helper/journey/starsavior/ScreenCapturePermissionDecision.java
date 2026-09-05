package helper.journey.starsavior;

/** Pure decision used by MainActivity so permission cancellation is regression-testable. */
final class ScreenCapturePermissionDecision {
    enum Action {
        START_CAPTURE_SERVICE,
        STAY_IDLE
    }

    private ScreenCapturePermissionDecision() {}

    static Action fromResult(boolean granted, boolean hasResultData) {
        return granted && hasResultData ? Action.START_CAPTURE_SERVICE : Action.STAY_IDLE;
    }
}
