package helper.journey.starsavior;

/** Thread-safe lifecycle for one MediaProjection capture session. */
final class CaptureSessionStateMachine {
    enum Phase {
        WAITING_FOR_PERMISSION,
        ACTIVE,
        CAPTURING,
        DESTROYED
    }

    private Phase phase = Phase.WAITING_FOR_PERMISSION;
    private int generation;

    synchronized void activate() {
        if (phase == Phase.DESTROYED) return;
        generation++;
        phase = Phase.ACTIVE;
    }

    synchronized void waitForPermission() {
        if (phase == Phase.DESTROYED) return;
        generation++;
        phase = Phase.WAITING_FOR_PERMISSION;
    }

    synchronized boolean beginCapture() {
        if (phase != Phase.ACTIVE) return false;
        generation++;
        phase = Phase.CAPTURING;
        return true;
    }

    synchronized boolean finishCapture() {
        if (phase != Phase.CAPTURING) return false;
        phase = Phase.ACTIVE;
        return true;
    }

    synchronized boolean finishCapture(int expectedGeneration) {
        if (generation != expectedGeneration) return false;
        return finishCapture();
    }

    synchronized boolean interruptForResize() {
        if (phase != Phase.CAPTURING) return false;
        generation++;
        phase = Phase.ACTIVE;
        return true;
    }

    synchronized boolean isCapturing() {
        return phase == Phase.CAPTURING;
    }

    synchronized boolean isActive(int expectedGeneration) {
        return (phase == Phase.ACTIVE || phase == Phase.CAPTURING)
                && generation == expectedGeneration;
    }

    synchronized int generation() {
        return generation;
    }

    synchronized Phase phase() {
        return phase;
    }

    synchronized void destroy() {
        generation++;
        phase = Phase.DESTROYED;
    }
}
