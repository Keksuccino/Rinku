package de.keksuccino.mcef;

/** Coordinates MCEF's one-shot CEF process lifecycle. */
final class MCEFInitializationController {
    enum BeginResult {
        STARTED,
        ALREADY_INITIALIZED,
        REJECTED
    }

    private enum State {
        READY,
        INITIALIZING,
        INITIALIZED,
        TERMINATED
    }

    private State state = State.READY;

    synchronized BeginResult beginInitialization() {
        return switch (state) {
            case READY -> {
                state = State.INITIALIZING;
                yield BeginResult.STARTED;
            }
            case INITIALIZED -> BeginResult.ALREADY_INITIALIZED;
            case INITIALIZING, TERMINATED -> BeginResult.REJECTED;
        };
    }

    synchronized void markInitialized() {
        if (state != State.INITIALIZING) throw new IllegalStateException("MCEF initialization was not in progress");
        state = State.INITIALIZED;
    }

    synchronized void terminate() {
        state = State.TERMINATED;
    }

    synchronized boolean canInitialize() {
        return state == State.READY;
    }

    synchronized boolean isInitialized() {
        return state == State.INITIALIZED;
    }
}
