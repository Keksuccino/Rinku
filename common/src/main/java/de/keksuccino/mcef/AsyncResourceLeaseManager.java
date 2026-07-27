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
 *
 *     You should have received a copy of the GNU Lesser General Public
 *     License along with this library; if not, write to the Free Software
 *     Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301
 *     USA
 */

package de.keksuccino.mcef;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Owns the latest asynchronous resource for each fixed stream until the render thread claims it.
 *
 * <p>Producers are serialized only while reserving and publishing an allocation. The expensive copy is performed
 * outside the state lock, close never waits for it, and at most one candidate allocation can exist at a time. A newer
 * resource replaces an older pending resource from the same stream and is marked for full resynchronization before
 * publication. Claimed resources have a single render-thread owner and are always released in {@code finally}.
 *
 * <p>This mailbox deliberately has no executor or future dependency. Minecraft can clear its event-loop queue without
 * completing queued futures, so a render-thread frame hook must call {@link #drain(int)} directly.
 */
final class AsyncResourceLeaseManager<K, T> implements AutoCloseable {
    private final Consumer<? super T> releaser;
    private final Consumer<? super T> resyncMarker;
    private final int maxPendingStreams;
    private final ReentrantLock stateLock = new ReentrantLock();
    private final Condition allocationFinished = stateLock.newCondition();
    private final Map<K, Lease<K, T>> pendingLeases = new HashMap<>();
    private final Set<K> resyncRequired = new HashSet<>();
    private volatile boolean accepting = true;
    private boolean allocationInProgress;
    private long resourceEpoch;
    private long allocationEpoch;
    private long nextSequence;
    private int runningLeaseCount;

    AsyncResourceLeaseManager(Consumer<? super T> releaser, Consumer<? super T> resyncMarker, int maxPendingStreams) {
        this.releaser = Objects.requireNonNull(releaser, "releaser");
        this.resyncMarker = Objects.requireNonNull(resyncMarker, "resyncMarker");
        if (maxPendingStreams <= 0) {
            throw new IllegalArgumentException("maxPendingStreams must be positive");
        }
        this.maxPendingStreams = maxPendingStreams;
    }

    boolean isAccepting() {
        return accepting;
    }

    boolean offer(K stream, Supplier<? extends T> resourceFactory, Consumer<? super T> task, Consumer<? super Throwable> failureHandler) {
        Objects.requireNonNull(stream, "stream");
        Objects.requireNonNull(resourceFactory, "resourceFactory");
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(failureHandler, "failureHandler");

        if (!reserveAllocation(stream)) {
            return false;
        }

        T resource;
        try {
            resource = Objects.requireNonNull(resourceFactory.get(), "resourceFactory returned null");
        } catch (Throwable failure) {
            finishFailedAllocation(stream);
            reportFailure(failureHandler, failure);
            return false;
        }

        Lease<K, T> replacedLease = null;
        boolean published = false;
        Throwable publicationFailure = null;
        stateLock.lock();
        try {
            if (accepting && allocationEpoch == resourceEpoch) {
                Lease<K, T> existingLease = pendingLeases.get(stream);
                if (existingLease != null || resyncRequired.remove(stream)) {
                    resyncMarker.accept(resource);
                }
                Lease<K, T> newLease = new Lease<>(stream, nextSequence++, resource, task, failureHandler);
                replacedLease = pendingLeases.put(stream, newLease);
                published = true;
            } else if (accepting) {
                resyncRequired.add(stream);
            }
        } catch (Throwable failure) {
            publicationFailure = failure;
            resyncRequired.add(stream);
        } finally {
            stateLock.unlock();
        }

        if (replacedLease != null) {
            releaseLease(replacedLease, null);
        }
        if (!published) {
            Throwable failure = releaseResource(resource, publicationFailure);
            finishAllocationReservation();
            if (failure != null) {
                reportFailure(failureHandler, failure);
            }
            return false;
        }
        finishAllocationReservation();
        return true;
    }

    int drain(int maxResources) {
        if (maxResources <= 0) {
            return 0;
        }

        int drained = 0;
        while (drained < maxResources) {
            Lease<K, T> lease = claimEarliestPending();
            if (lease == null) {
                break;
            }
            runLease(lease);
            drained++;
        }
        return drained;
    }

    void requireResync(K stream) {
        Objects.requireNonNull(stream, "stream");
        stateLock.lock();
        try {
            markResyncLocked(stream);
        } finally {
            stateLock.unlock();
        }
    }

    boolean consumeResync(K stream) {
        Objects.requireNonNull(stream, "stream");
        stateLock.lock();
        try {
            return resyncRequired.remove(stream);
        } finally {
            stateLock.unlock();
        }
    }

    void abandonPending() {
        List<Lease<K, T>> abandonedLeases;
        stateLock.lock();
        try {
            resourceEpoch++;
            abandonedLeases = new ArrayList<>(pendingLeases.values());
            pendingLeases.clear();
            if (accepting) {
                for (Lease<K, T> lease : abandonedLeases) {
                    resyncRequired.add(lease.stream());
                }
            }
        } finally {
            stateLock.unlock();
        }
        releaseLeases(abandonedLeases);
    }

    @Override
    public void close() {
        List<Lease<K, T>> abandonedLeases;
        stateLock.lock();
        try {
            if (!accepting) {
                return;
            }
            accepting = false;
            allocationFinished.signalAll();
            abandonedLeases = new ArrayList<>(pendingLeases.values());
            pendingLeases.clear();
            resyncRequired.clear();
        } finally {
            stateLock.unlock();
        }
        releaseLeases(abandonedLeases);
    }

    int pendingLeaseCount() {
        stateLock.lock();
        try {
            return pendingLeases.size();
        } finally {
            stateLock.unlock();
        }
    }

    int runningLeaseCount() {
        stateLock.lock();
        try {
            return runningLeaseCount;
        } finally {
            stateLock.unlock();
        }
    }

    private boolean reserveAllocation(K stream) {
        stateLock.lock();
        try {
            while (accepting && allocationInProgress) {
                allocationFinished.awaitUninterruptibly();
            }
            if (!accepting) {
                return false;
            }
            if (!pendingLeases.containsKey(stream) && pendingLeases.size() >= maxPendingStreams) {
                resyncRequired.add(stream);
                return false;
            }
            allocationInProgress = true;
            allocationEpoch = resourceEpoch;
            return true;
        } finally {
            stateLock.unlock();
        }
    }

    private void finishFailedAllocation(K stream) {
        stateLock.lock();
        try {
            allocationInProgress = false;
            if (accepting) {
                resyncRequired.add(stream);
            }
            allocationFinished.signalAll();
        } finally {
            stateLock.unlock();
        }
    }

    private void finishAllocationReservation() {
        stateLock.lock();
        try {
            allocationInProgress = false;
            allocationFinished.signalAll();
        } finally {
            stateLock.unlock();
        }
    }

    private Lease<K, T> claimEarliestPending() {
        stateLock.lock();
        try {
            Lease<K, T> earliest = null;
            for (Lease<K, T> candidate : pendingLeases.values()) {
                if (earliest == null || Long.compareUnsigned(candidate.sequence(), earliest.sequence()) < 0) {
                    earliest = candidate;
                }
            }
            if (earliest != null && pendingLeases.remove(earliest.stream(), earliest)) {
                runningLeaseCount++;
                return earliest;
            }
            return null;
        } finally {
            stateLock.unlock();
        }
    }

    private void runLease(Lease<K, T> lease) {
        Throwable failure = null;
        try {
            lease.task().accept(lease.resource());
        } catch (Throwable taskFailure) {
            failure = taskFailure;
            stateLock.lock();
            try {
                markResyncLocked(lease.stream());
            } finally {
                stateLock.unlock();
            }
        } finally {
            failure = releaseResource(lease.resource(), failure);
            stateLock.lock();
            try {
                runningLeaseCount--;
            } finally {
                stateLock.unlock();
            }
        }
        if (failure != null) {
            reportFailure(lease.failureHandler(), failure);
        }
    }

    private void markResyncLocked(K stream) {
        Lease<K, T> pendingLease = pendingLeases.get(stream);
        if (pendingLease == null) {
            if (accepting) {
                resyncRequired.add(stream);
            }
            return;
        }
        try {
            resyncMarker.accept(pendingLease.resource());
        } catch (Throwable failure) {
            resyncRequired.add(stream);
            reportFailure(pendingLease.failureHandler(), failure);
        }
    }

    private void releaseLeases(List<Lease<K, T>> leases) {
        for (Lease<K, T> lease : leases) {
            releaseLease(lease, null);
        }
    }

    private void releaseLease(Lease<K, T> lease, Throwable primaryFailure) {
        Throwable failure = releaseResource(lease.resource(), primaryFailure);
        if (failure != null) {
            reportFailure(lease.failureHandler(), failure);
        }
    }

    private Throwable releaseResource(T resource, Throwable primaryFailure) {
        try {
            releaser.accept(resource);
        } catch (Throwable releaseFailure) {
            if (primaryFailure == null) {
                return releaseFailure;
            }
            if (primaryFailure != releaseFailure) {
                primaryFailure.addSuppressed(releaseFailure);
            }
        }
        return primaryFailure;
    }

    private static void reportFailure(Consumer<? super Throwable> failureHandler, Throwable failure) {
        try {
            failureHandler.accept(failure);
        } catch (Throwable handlerFailure) {
            if (handlerFailure != failure) {
                failure.addSuppressed(handlerFailure);
            }
        }
    }

    private record Lease<K, T>(K stream, long sequence, T resource, Consumer<? super T> task, Consumer<? super Throwable> failureHandler) {}
}
