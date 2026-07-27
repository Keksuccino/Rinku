/*
 *     MCEF (Minecraft Chromium Embedded Framework)
 *     Copyright (C) 2023 CinemaMod Group
 *
 *     This library is free software; you can redistribute it and/or
 *     modify it under the terms of the GNU Lesser General Public
 *     License as published by the Free Software Foundation; either
 *     version 2.1 of the License, or (at your option) any later version.
 *
 *     This library is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *     Lesser General Public License for more details.
 */

package de.keksuccino.mcef;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Serializes one emulated off-screen drag session without depending on CEF's native runtime.
 *
 * <p>Publication is changed under {@link #lock}, but native callbacks and disposal always run
 * without it held. Callbacks may synchronously wait for another thread that re-enters Java; the
 * explicit {@link Phase} makes those lifecycle calls harmless without creating a cross-thread
 * deadlock. No resource is disposed until every required terminal callback has been attempted.
 */
final class MCEFDragSessionController<T> {
    private final Object lock = new Object();
    private final Consumer<? super T> disposer;
    private final int noCursorOverride;
    private Session<T> current;
    private Session<T> entering;
    private Phase phase = Phase.IDLE;
    private boolean closed;

    MCEFDragSessionController(Consumer<? super T> disposer, int noCursorOverride) {
        this.disposer = Objects.requireNonNull(disposer, "disposer");
        this.noCursorOverride = noCursorOverride;
    }

    /**
     * Provisions a new session and retires any previously handled session first.
     *
     * <p>Ownership transfers to this controller only when this method returns normally. A normal
     * {@code false} return therefore disposes {@code resource}, while an exception leaves the
     * provisional resource for the callback delegator to dispose under its exceptional-transfer
     * contract.
     */
    boolean start(T resource, int allowedOperations, int x, int y, int modifiers, Callbacks<T> callbacks) {
        Objects.requireNonNull(resource, "resource");
        Objects.requireNonNull(callbacks, "callbacks");

        Session<T> retired = null;
        boolean admitted;
        synchronized (lock) {
            admitted = !closed && phase == Phase.IDLE;
            if (admitted) {
                phase = Phase.RETIRING;
                retired = current;
                current = null;
            }
        }
        if (!admitted) {
            disposer.accept(resource);
            return false;
        }

        Throwable retirementFailure = retired == null ? null : cancelHandledSession(retired, callbacks);
        if (retirementFailure != null) {
            finishTransition();
            rethrow(retirementFailure);
        }

        Session<T> candidate = new Session<>(resource, allowedOperations, noCursorOverride);
        synchronized (lock) {
            if (closed) {
                phase = Phase.IDLE;
            } else {
                entering = candidate;
                phase = Phase.ENTERING;
            }
        }
        if (!isEntering(candidate)) {
            finishTransition();
            disposer.accept(resource);
            return false;
        }

        try {
            callbacks.targetEnter(resource, x, y, modifiers, allowedOperations);
        } catch (RuntimeException | Error enterFailure) {
            Throwable failure = runAndMerge(enterFailure, callbacks::targetLeave);
            finishTransition();
            rethrow(failure);
        }

        boolean accepted;
        synchronized (lock) {
            entering = null;
            accepted = !closed;
            if (accepted) {
                current = candidate;
                phase = Phase.IDLE;
            } else {
                phase = Phase.RETIRING;
            }
        }
        if (accepted) return true;

        Throwable failure = runAndMerge(null, callbacks::targetLeave);
        failure = disposeAndMerge(failure, candidate);
        finishTransition();
        rethrow(failure);
        return false;
    }

    /** Atomically updates the operation and its corresponding cursor for the same session. */
    boolean updateOperation(int operation, int cursorOverride) {
        synchronized (lock) {
            Session<T> session = visibleSession();
            if (session == null) return false;
            boolean cursorChanged = session.cursorOverride != cursorOverride;
            session.operation = operation;
            session.cursorOverride = cursorOverride;
            return cursorChanged;
        }
    }

    boolean finish(int x, int y, int modifiers, Callbacks<T> callbacks) {
        Objects.requireNonNull(callbacks, "callbacks");
        Session<T> completed = detachCurrent();
        if (completed == null) return false;

        Throwable failure = finishHandledSession(completed, x, y, modifiers, callbacks);
        finishTransition();
        rethrow(failure);
        return true;
    }

    boolean cancel(Callbacks<T> callbacks) {
        Objects.requireNonNull(callbacks, "callbacks");
        Session<T> cancelled = detachCurrent();
        if (cancelled == null) return false;

        Throwable failure = cancelHandledSession(cancelled, callbacks);
        finishTransition();
        rethrow(failure);
        return true;
    }

    void close(Callbacks<T> callbacks) {
        Objects.requireNonNull(callbacks, "callbacks");
        Session<T> cancelled;
        synchronized (lock) {
            closed = true;
            if (phase != Phase.IDLE || current == null) return;
            phase = Phase.RETIRING;
            cancelled = current;
            current = null;
        }

        Throwable failure = cancelHandledSession(cancelled, callbacks);
        finishTransition();
        rethrow(failure);
    }

    boolean isDragging() {
        synchronized (lock) {
            return visibleSession() != null;
        }
    }

    boolean isTransitioning() {
        synchronized (lock) {
            return phase != Phase.IDLE;
        }
    }

    T resource() {
        synchronized (lock) {
            Session<T> session = visibleSession();
            return session == null ? null : session.resource;
        }
    }

    int allowedOperations() {
        synchronized (lock) {
            Session<T> session = visibleSession();
            return session == null ? 0 : session.allowedOperations;
        }
    }

    int virtualCursor(int actualCursor) {
        synchronized (lock) {
            Session<T> session = visibleSession();
            return session == null || session.cursorOverride == noCursorOverride ? actualCursor : session.cursorOverride;
        }
    }

    private boolean isEntering(Session<T> candidate) {
        synchronized (lock) {
            return !closed && phase == Phase.ENTERING && entering == candidate;
        }
    }

    private Session<T> detachCurrent() {
        synchronized (lock) {
            if (phase != Phase.IDLE || current == null) return null;
            phase = Phase.RETIRING;
            Session<T> detached = current;
            current = null;
            return detached;
        }
    }

    private void finishTransition() {
        synchronized (lock) {
            entering = null;
            phase = Phase.IDLE;
        }
    }

    private Session<T> visibleSession() {
        if (phase == Phase.ENTERING) return entering;
        return phase == Phase.IDLE ? current : null;
    }

    private Throwable finishHandledSession(Session<T> session, int x, int y, int modifiers, Callbacks<T> callbacks) {
        int operation = session.operation;
        Throwable failure = runAndMerge(null, () -> callbacks.targetDrop(x, y, modifiers));
        failure = runAndMerge(failure, () -> callbacks.sourceEndedAt(x, y, operation));
        failure = runAndMerge(failure, callbacks::sourceSystemDragEnded);
        return disposeAndMerge(failure, session);
    }

    private Throwable cancelHandledSession(Session<T> session, Callbacks<T> callbacks) {
        Throwable failure = runAndMerge(null, callbacks::targetLeave);
        failure = runAndMerge(failure, callbacks::sourceSystemDragEnded);
        return disposeAndMerge(failure, session);
    }

    private Throwable disposeAndMerge(Throwable failure, Session<T> session) {
        return runAndMerge(failure, () -> disposer.accept(session.resource));
    }

    private static Throwable runAndMerge(Throwable primaryFailure, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException | Error failure) {
            if (primaryFailure == null) return failure;
            if (primaryFailure != failure) primaryFailure.addSuppressed(failure);
        }
        return primaryFailure;
    }

    private static void rethrow(Throwable failure) {
        if (failure instanceof RuntimeException runtimeFailure) throw runtimeFailure;
        if (failure instanceof Error errorFailure) throw errorFailure;
    }

    interface Callbacks<T> {
        void targetEnter(T resource, int x, int y, int modifiers, int allowedOperations);

        void targetDrop(int x, int y, int modifiers);

        void targetLeave();

        void sourceEndedAt(int x, int y, int operation);

        void sourceSystemDragEnded();
    }

    private enum Phase {
        IDLE,
        ENTERING,
        RETIRING
    }

    private static final class Session<T> {
        private final T resource;
        private final int allowedOperations;
        private int operation;
        private int cursorOverride;

        private Session(T resource, int allowedOperations, int noCursorOverride) {
            this.resource = resource;
            this.allowedOperations = allowedOperations;
            cursorOverride = noCursorOverride;
        }
    }
}
