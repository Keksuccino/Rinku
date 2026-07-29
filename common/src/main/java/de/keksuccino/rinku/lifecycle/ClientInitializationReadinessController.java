package de.keksuccino.rinku.lifecycle;

/**
 * Latches native client initialization only after loader startup has remained stable across a complete frame.
 * The retry flag deliberately remembers only that work is pending; the caller must use Minecraft's current screen
 * when consuming it because startup may replace several screens while the loading overlay is active.
 */
public final class ClientInitializationReadinessController {

    static final int REQUIRED_STABLE_TICKS = 2;

    private boolean ready;
    private boolean retryPending;
    private int stableTicks;

    public ClientInitializationReadinessController() {}

    public synchronized boolean shouldDeferInitialization(boolean requiresDeferral) {
        if (!requiresDeferral) {
            ready = true;
            return false;
        }
        if (ready) return false;
        retryPending = true;
        return true;
    }

    public synchronized boolean observeTick(boolean requiresDeferral, boolean stableCandidate) {
        if (!requiresDeferral) {
            ready = true;
            stableTicks = REQUIRED_STABLE_TICKS;
            return consumeRetry();
        }
        if (ready) return false;
        if (!stableCandidate) {
            stableTicks = 0;
            return false;
        }
        stableTicks++;
        if (stableTicks < REQUIRED_STABLE_TICKS) return false;
        ready = true;
        return consumeRetry();
    }

    private boolean consumeRetry() {
        boolean retry = retryPending;
        retryPending = false;
        return retry;
    }
}
