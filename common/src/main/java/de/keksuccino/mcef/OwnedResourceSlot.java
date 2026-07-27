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

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Owns at most one resource and coordinates replacement, use, and terminal shutdown.
 *
 * <p>Publication is always cleared before a retired resource is disposed. User code and the
 * disposer run without {@link #lock} held because either may enter native code and re-enter Java.
 */
final class OwnedResourceSlot<T> implements AutoCloseable {
    private final Object lock = new Object();
    private final Consumer<? super T> disposer;
    private final Function<? super T, ?> resourceIdentity;
    private Entry<T> current;
    private boolean closed;

    OwnedResourceSlot(Consumer<? super T> disposer) {
        this(disposer, Function.identity());
    }

    OwnedResourceSlot(Consumer<? super T> disposer, Function<? super T, ?> resourceIdentity) {
        this.disposer = Objects.requireNonNull(disposer, "disposer");
        this.resourceIdentity = Objects.requireNonNull(resourceIdentity, "resourceIdentity");
    }

    /**
     * Transfers ownership of {@code resource} into this slot.
     *
     * @return {@code false} when terminal shutdown has already sealed the slot. The rejected
     *         resource is still disposed by this method.
     */
    boolean replace(T resource) {
        Objects.requireNonNull(resource, "resource");
        Object identity = Objects.requireNonNull(resourceIdentity.apply(resource), "resource identity");

        T resourceToDispose = null;
        boolean accepted;
        synchronized (lock) {
            if (closed) {
                accepted = false;
                resourceToDispose = resource;
            } else if (current != null && current.identity == identity) {
                // Re-publication of the same underlying resource updates associated immutable
                // metadata without manufacturing a second ownership claim.
                current.resource = resource;
                return true;
            } else {
                Entry<T> retired = detachCurrentLocked();
                current = new Entry<>(resource, identity);
                resourceToDispose = claimDisposalLocked(retired);
                accepted = true;
            }
        }

        dispose(resourceToDispose);
        return accepted;
    }

    T get() {
        synchronized (lock) {
            return current == null ? null : current.resource;
        }
    }

    boolean isCurrent(T resource) {
        synchronized (lock) {
            return current != null && current.resource == resource;
        }
    }

    /**
     * Uses {@code expected} only while it is still the published resource. A concurrent reset or
     * close clears publication immediately but defers disposal until {@code action} returns.
     */
    boolean useIfCurrent(T expected, Consumer<? super T> action) {
        return useIfCurrent(expected, action, false);
    }

    /**
     * Uses {@code expected}, but returns its ownership to the caller if {@code action} fails. This
     * supports callback APIs whose ownership transfer occurs only after the callback returns
     * normally. The caller remains responsible for disposing the abandoned resource.
     */
    boolean useIfCurrentAndAbandonOnFailure(T expected, Consumer<? super T> action) {
        return useIfCurrent(expected, action, true);
    }

    private boolean useIfCurrent(T expected, Consumer<? super T> action, boolean abandonOnFailure) {
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(action, "action");

        Entry<T> entry;
        synchronized (lock) {
            if (current == null || current.resource != expected) {
                return false;
            }
            entry = current;
            entry.activeUses++;
        }

        use(entry, expected, action, abandonOnFailure);
        return true;
    }

    /**
     * Clears the current ownership before running one final use. Only the thread that detaches the
     * resource runs {@code action}; concurrent and repeated reset attempts are no-ops.
     */
    boolean clearAndUse(Consumer<? super T> action) {
        Objects.requireNonNull(action, "action");

        Entry<T> retired;
        T resource;
        synchronized (lock) {
            retired = detachCurrentLocked();
            if (retired == null) {
                return false;
            }
            retired.activeUses++;
            resource = retired.resource;
        }

        use(retired, resource, action, false);
        return true;
    }

    /**
     * Clears and disposes the current resource. This operation is idempotent and does not seal the
     * slot against a later replacement.
     */
    boolean reset() {
        T resourceToDispose;
        boolean hadResource;
        synchronized (lock) {
            Entry<T> retired = detachCurrentLocked();
            hadResource = retired != null;
            resourceToDispose = claimDisposalLocked(retired);
        }

        dispose(resourceToDispose);
        return hadResource;
    }

    /**
     * Atomically seals the slot against future ownership and drains its current resource.
     */
    @Override
    public void close() {
        T resourceToDispose;
        synchronized (lock) {
            closed = true;
            resourceToDispose = claimDisposalLocked(detachCurrentLocked());
        }

        dispose(resourceToDispose);
    }

    boolean isClosed() {
        synchronized (lock) {
            return closed;
        }
    }

    private Entry<T> detachCurrentLocked() {
        Entry<T> retired = current;
        current = null;
        if (retired != null) {
            retired.owned = false;
        }
        return retired;
    }

    private T claimDisposalLocked(Entry<T> entry) {
        if (entry == null || entry.owned || entry.activeUses != 0 || entry.disposalClaimed) {
            return null;
        }
        entry.disposalClaimed = true;
        return entry.resource;
    }

    private void use(Entry<T> entry, T resource, Consumer<? super T> action, boolean abandonOnFailure) {
        Throwable actionFailure = null;
        try {
            action.accept(resource);
        } catch (RuntimeException | Error failure) {
            actionFailure = failure;
            if (abandonOnFailure) {
                abandon(entry);
            }
            throw failure;
        } finally {
            try {
                endUse(entry);
            } catch (RuntimeException | Error disposalFailure) {
                if (actionFailure == null) {
                    throw disposalFailure;
                }
                if (actionFailure != disposalFailure) {
                    actionFailure.addSuppressed(disposalFailure);
                }
            }
        }
    }

    private void abandon(Entry<T> entry) {
        synchronized (lock) {
            if (current == entry) {
                detachCurrentLocked();
            }
            // An active use prevented reset, close, or replacement from claiming disposal. Mark
            // the release handled so ownership can return to the callback's caller on failure.
            entry.owned = false;
            entry.disposalClaimed = true;
        }
    }

    private void endUse(Entry<T> entry) {
        T resourceToDispose;
        synchronized (lock) {
            entry.activeUses--;
            if (entry.activeUses < 0) {
                entry.activeUses = 0;
                throw new IllegalStateException("Owned resource use count became negative");
            }
            resourceToDispose = claimDisposalLocked(entry);
        }

        dispose(resourceToDispose);
    }

    private void dispose(T resource) {
        if (resource != null) {
            disposer.accept(resource);
        }
    }

    private static final class Entry<T> {
        private T resource;
        private final Object identity;
        private int activeUses;
        private boolean owned = true;
        private boolean disposalClaimed;

        private Entry(T resource, Object identity) {
            this.resource = resource;
            this.identity = identity;
        }
    }
}
