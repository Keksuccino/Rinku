/*
 *     MCEF (Minecraft Chromium Embedded Framework)
 *     Copyright (C) 2023 CinemaMod Group
 *
 *     This library is free software; you can redistribute it and/or
 *     modify it under the terms of the GNU Lesser General Public
 *     License as published by the Free Software Foundation; either
 *     version 2.1 of the License, or (at your option) any later version.
 */

package de.keksuccino.mcef;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Thread-safe registration with render-thread-only pumping and terminal shutdown.
 * Snapshot iteration keeps registration operations short and isolates every browser action so one failing browser
 * cannot prevent the remaining browsers from painting or cleaning up.
 */
final class RenderThreadMailboxCoordinator<T> {
    private final Object stateLock = new Object();
    private final Set<T> registrations = new LinkedHashSet<>();
    private boolean shutdown;

    boolean register(T registration) {
        Objects.requireNonNull(registration, "registration");
        synchronized (stateLock) {
            if (shutdown) {
                return false;
            }
            registrations.add(registration);
            return true;
        }
    }

    void unregister(T registration) {
        synchronized (stateLock) {
            registrations.remove(registration);
        }
    }

    void pump(Consumer<? super T> action, BiConsumer<? super T, ? super Throwable> failureHandler) {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(failureHandler, "failureHandler");
        for (T registration : snapshot(false)) {
            invokeIsolated(registration, action, failureHandler);
        }
    }

    void shutdown(Consumer<? super T> action, BiConsumer<? super T, ? super Throwable> failureHandler) {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(failureHandler, "failureHandler");
        for (T registration : snapshot(true)) {
            invokeIsolated(registration, action, failureHandler);
        }
    }

    int registrationCount() {
        synchronized (stateLock) {
            return registrations.size();
        }
    }

    boolean isShutdown() {
        synchronized (stateLock) {
            return shutdown;
        }
    }

    private List<T> snapshot(boolean terminate) {
        synchronized (stateLock) {
            if (shutdown) {
                return List.of();
            }
            List<T> snapshot = new ArrayList<>(registrations);
            if (terminate) {
                shutdown = true;
                registrations.clear();
            }
            return snapshot;
        }
    }

    private static <T> void invokeIsolated(T registration, Consumer<? super T> action, BiConsumer<? super T, ? super Throwable> failureHandler) {
        try {
            action.accept(registration);
        } catch (Throwable failure) {
            try {
                failureHandler.accept(registration, failure);
            } catch (Throwable reportingFailure) {
                if (reportingFailure != failure) {
                    failure.addSuppressed(reportingFailure);
                }
            }
        }
    }
}
