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

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AsyncResourceLeaseManagerTest {
    @Test
    void normalDrainUsesAndReleasesResourceOnce() {
        AtomicInteger releases = new AtomicInteger();
        List<TestResource> used = new ArrayList<>();
        AsyncResourceLeaseManager<Stream, TestResource> manager = newManager(releases);

        assertTrue(manager.offer(Stream.VIEW, () -> new TestResource(1), used::add, failure -> {}));
        assertEquals(1, manager.pendingLeaseCount());
        assertEquals(1, manager.drain(2));

        assertEquals(List.of(1), ids(used));
        assertEquals(1, releases.get());
        assertEquals(0, manager.pendingLeaseCount());
        assertEquals(0, manager.runningLeaseCount());
    }

    @Test
    void sameStreamReplacementReleasesOldAndForcesFullResyncOnLatest() {
        AtomicInteger releases = new AtomicInteger();
        List<TestResource> used = new ArrayList<>();
        AsyncResourceLeaseManager<Stream, TestResource> manager = newManager(releases);

        assertTrue(manager.offer(Stream.VIEW, () -> new TestResource(1), used::add, failure -> {}));
        assertTrue(manager.offer(Stream.VIEW, () -> new TestResource(2), used::add, failure -> {}));

        assertEquals(1, releases.get());
        assertEquals(1, manager.pendingLeaseCount());
        assertEquals(1, manager.drain(2));
        assertEquals(List.of(2), ids(used));
        assertTrue(used.getFirst().fullResync());
        assertEquals(2, releases.get());
    }

    @Test
    void viewAndPopupRemainIndependentAndDrainInPublicationOrder() {
        AtomicInteger releases = new AtomicInteger();
        List<TestResource> used = new ArrayList<>();
        AsyncResourceLeaseManager<Stream, TestResource> manager = newManager(releases);

        assertTrue(manager.offer(Stream.POPUP, () -> new TestResource(1), used::add, failure -> {}));
        assertTrue(manager.offer(Stream.VIEW, () -> new TestResource(2), used::add, failure -> {}));

        assertEquals(2, manager.drain(2));
        assertEquals(List.of(1, 2), ids(used));
        assertFalse(used.get(0).fullResync());
        assertFalse(used.get(1).fullResync());
        assertEquals(2, releases.get());
    }

    @Test
    void replacementDebtIsIndependentForViewAndPopup() {
        AtomicInteger releases = new AtomicInteger();
        List<TestResource> used = new ArrayList<>();
        AsyncResourceLeaseManager<Stream, TestResource> manager = newManager(releases);

        assertTrue(manager.offer(Stream.VIEW, () -> new TestResource(1), used::add, failure -> {}));
        assertTrue(manager.offer(Stream.POPUP, () -> new TestResource(2), used::add, failure -> {}));
        assertTrue(manager.offer(Stream.VIEW, () -> new TestResource(3), used::add, failure -> {}));

        assertEquals(2, manager.drain(2));
        assertEquals(List.of(2, 3), ids(used));
        assertFalse(used.get(0).fullResync());
        assertTrue(used.get(1).fullResync());
        assertEquals(3, releases.get());
    }

    @Test
    void abandonedMailboxReleasesResourcesRearmsAndForcesRecovery() {
        AtomicInteger releases = new AtomicInteger();
        List<TestResource> used = new ArrayList<>();
        AsyncResourceLeaseManager<Stream, TestResource> manager = newManager(releases);

        assertTrue(manager.offer(Stream.VIEW, () -> new TestResource(1), used::add, failure -> {}));
        assertTrue(manager.offer(Stream.POPUP, () -> new TestResource(2), used::add, failure -> {}));
        manager.abandonPending();

        assertEquals(2, releases.get());
        assertEquals(0, manager.pendingLeaseCount());
        assertTrue(manager.isAccepting());
        assertTrue(manager.offer(Stream.VIEW, () -> new TestResource(3), used::add, failure -> {}));
        assertTrue(manager.offer(Stream.POPUP, () -> new TestResource(4), used::add, failure -> {}));
        assertEquals(2, manager.drain(2));
        assertTrue(used.get(0).fullResync());
        assertTrue(used.get(1).fullResync());
        assertEquals(4, releases.get());
    }

    @Test
    void abandonmentDuringAllocationRejectsLateResourceAndForcesRecovery() {
        AtomicInteger releases = new AtomicInteger();
        AtomicReference<Boolean> accepted = new AtomicReference<>();
        CountDownLatch allocationStarted = new CountDownLatch(1);
        CountDownLatch finishAllocation = new CountDownLatch(1);
        List<TestResource> used = new ArrayList<>();
        List<Throwable> failures = new ArrayList<>();
        AsyncResourceLeaseManager<Stream, TestResource> manager = newManager(releases);

        Supplier<TestResource> resourceFactory = () -> {
            allocationStarted.countDown();
            await(finishAllocation);
            return new TestResource(1);
        };
        Runnable allocation = () -> accepted.set(manager.offer(Stream.VIEW, resourceFactory, used::add, failures::add));
        Thread allocationThread = Thread.ofPlatform().start(allocation);
        await(allocationStarted);

        manager.abandonPending();
        finishAllocation.countDown();
        join(allocationThread);

        assertFalse(accepted.get());
        assertTrue(used.isEmpty());
        assertTrue(failures.isEmpty());
        assertEquals(1, releases.get());
        assertTrue(manager.offer(Stream.VIEW, () -> new TestResource(2), used::add, failure -> {}));
        assertEquals(1, manager.drain(1));
        assertTrue(used.getFirst().fullResync());
        assertEquals(2, releases.get());
    }

    @Test
    void directConsumerCanConsumeOutstandingRecoveryExactlyOnce() {
        AtomicInteger releases = new AtomicInteger();
        AsyncResourceLeaseManager<Stream, TestResource> manager = newManager(releases);

        manager.requireResync(Stream.VIEW);

        assertTrue(manager.consumeResync(Stream.VIEW));
        assertFalse(manager.consumeResync(Stream.VIEW));
        assertFalse(manager.consumeResync(Stream.POPUP));
    }

    @Test
    void taskFailureReleasesBeforeReportingAndForcesNextRecovery() {
        AtomicInteger releases = new AtomicInteger();
        AtomicInteger releasesSeenByHandler = new AtomicInteger(-1);
        List<Throwable> failures = new ArrayList<>();
        IllegalStateException taskFailure = new IllegalStateException("render failed");
        AsyncResourceLeaseManager<Stream, TestResource> manager = newManager(releases);

        Consumer<TestResource> failingTask = resource -> { throw taskFailure; };
        Consumer<Throwable> failureHandler = failure -> {
            releasesSeenByHandler.set(releases.get());
            failures.add(failure);
        };
        assertTrue(manager.offer(Stream.VIEW, () -> new TestResource(1), failingTask, failureHandler));
        assertEquals(1, manager.drain(1));
        assertEquals(1, releasesSeenByHandler.get());
        assertSame(taskFailure, failures.getFirst());

        List<TestResource> recovered = new ArrayList<>();
        assertTrue(manager.offer(Stream.VIEW, () -> new TestResource(2), recovered::add, failures::add));
        assertEquals(1, manager.drain(1));
        assertTrue(recovered.getFirst().fullResync());
        assertEquals(2, releases.get());
    }

    @Test
    void taskFailureMarksAlreadyPendingFrameForRecovery() {
        AtomicInteger releases = new AtomicInteger();
        CountDownLatch taskStarted = new CountDownLatch(1);
        CountDownLatch finishTask = new CountDownLatch(1);
        List<TestResource> recovered = new ArrayList<>();
        List<Throwable> failures = new ArrayList<>();
        AsyncResourceLeaseManager<Stream, TestResource> manager = newManager(releases);
        IllegalStateException taskFailure = new IllegalStateException("render failed");

        Consumer<TestResource> failingTask = resource -> {
            taskStarted.countDown();
            await(finishTask);
            throw taskFailure;
        };
        assertTrue(manager.offer(Stream.VIEW, () -> new TestResource(1), failingTask, failures::add));
        Thread drainThread = Thread.ofPlatform().start(() -> manager.drain(1));
        await(taskStarted);
        assertTrue(manager.offer(Stream.VIEW, () -> new TestResource(2), recovered::add, failure -> {}));
        finishTask.countDown();
        join(drainThread);

        assertEquals(1, manager.drain(1));
        assertEquals(List.of(taskFailure), failures);
        assertTrue(recovered.getFirst().fullResync());
        assertEquals(2, releases.get());
    }

    @Test
    void factoryFailureCreatesRecoveryDebtWithoutPublishing() {
        AtomicInteger releases = new AtomicInteger();
        List<Throwable> failures = new ArrayList<>();
        IllegalStateException allocationFailure = new IllegalStateException("allocation failed");
        AsyncResourceLeaseManager<Stream, TestResource> manager = newManager(releases);

        Supplier<TestResource> failingFactory = () -> { throw allocationFailure; };
        assertFalse(manager.offer(Stream.VIEW, failingFactory, resource -> {}, failures::add));
        assertSame(allocationFailure, failures.getFirst());
        assertEquals(0, manager.pendingLeaseCount());

        List<TestResource> used = new ArrayList<>();
        assertTrue(manager.offer(Stream.VIEW, () -> new TestResource(1), used::add, failures::add));
        manager.drain(1);
        assertTrue(used.getFirst().fullResync());
        assertEquals(1, releases.get());
    }

    @Test
    void thirdStreamIsRejectedBeforeAllocationAndRecoversWhenCapacityExists() {
        AtomicInteger allocations = new AtomicInteger();
        AtomicInteger releases = new AtomicInteger();
        List<TestResource> used = new ArrayList<>();
        AsyncResourceLeaseManager<Stream, TestResource> manager = newManager(releases);

        assertTrue(manager.offer(Stream.VIEW, () -> new TestResource(1), used::add, failure -> {}));
        assertTrue(manager.offer(Stream.POPUP, () -> new TestResource(2), used::add, failure -> {}));
        assertFalse(manager.offer(Stream.EXTRA, () -> new TestResource(allocations.incrementAndGet()), used::add, failure -> {}));
        assertEquals(0, allocations.get());

        manager.drain(1);
        assertTrue(manager.offer(Stream.EXTRA, () -> new TestResource(3), used::add, failure -> {}));
        manager.drain(2);
        TestResource extra = used.stream().filter(resource -> resource.id() == 3).findFirst().orElseThrow();
        assertTrue(extra.fullResync());
        assertEquals(3, releases.get());
    }

    @Test
    void closeDuringAllocationReturnsWithoutWaitingAndReleasesLateResource() {
        AtomicInteger releases = new AtomicInteger();
        AtomicInteger uses = new AtomicInteger();
        AtomicReference<Boolean> accepted = new AtomicReference<>();
        CountDownLatch allocationStarted = new CountDownLatch(1);
        CountDownLatch finishAllocation = new CountDownLatch(1);
        List<Throwable> failures = new ArrayList<>();
        AsyncResourceLeaseManager<Stream, TestResource> manager = newManager(releases);

        Supplier<TestResource> resourceFactory = () -> {
            allocationStarted.countDown();
            await(finishAllocation);
            return new TestResource(1);
        };
        Runnable allocation = () -> accepted.set(manager.offer(Stream.VIEW, resourceFactory, resource -> uses.incrementAndGet(), failures::add));
        Thread allocationThread = Thread.ofPlatform().start(allocation);
        await(allocationStarted);

        manager.close();
        assertFalse(manager.isAccepting());
        finishAllocation.countDown();
        join(allocationThread);

        assertFalse(accepted.get());
        assertEquals(0, uses.get());
        assertTrue(failures.isEmpty());
        assertEquals(1, releases.get());
    }

    @Test
    void closeWakesProducerWaitingBehindAllocationWithoutAllocatingIt() {
        AtomicInteger secondAllocations = new AtomicInteger();
        AtomicInteger releases = new AtomicInteger();
        CountDownLatch firstAllocationStarted = new CountDownLatch(1);
        CountDownLatch finishFirstAllocation = new CountDownLatch(1);
        AtomicReference<Boolean> firstAccepted = new AtomicReference<>();
        AtomicReference<Boolean> secondAccepted = new AtomicReference<>();
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
        AsyncResourceLeaseManager<Stream, TestResource> manager = newManager(releases);

        Supplier<TestResource> firstFactory = () -> {
            firstAllocationStarted.countDown();
            await(finishFirstAllocation);
            return new TestResource(1);
        };
        Runnable firstAllocation = () -> firstAccepted.set(manager.offer(Stream.VIEW, firstFactory, resource -> {}, failures::add));
        Thread first = Thread.ofPlatform().start(firstAllocation);
        await(firstAllocationStarted);
        Runnable secondAllocation = () -> secondAccepted.set(manager.offer(Stream.POPUP, () -> new TestResource(secondAllocations.incrementAndGet()), resource -> {}, failures::add));
        Thread second = Thread.ofPlatform().start(secondAllocation);

        manager.close();
        finishFirstAllocation.countDown();
        join(first);
        join(second);

        assertFalse(firstAccepted.get());
        assertFalse(secondAccepted.get());
        assertEquals(0, secondAllocations.get());
        assertTrue(failures.isEmpty());
        assertEquals(1, releases.get());
    }

    @Test
    void closeDuringRunDefersReleaseUntilTaskExit() {
        AtomicInteger releases = new AtomicInteger();
        AtomicInteger uses = new AtomicInteger();
        CountDownLatch taskStarted = new CountDownLatch(1);
        CountDownLatch finishTask = new CountDownLatch(1);
        List<Throwable> failures = new ArrayList<>();
        AsyncResourceLeaseManager<Stream, TestResource> manager = newManager(releases);

        Consumer<TestResource> blockingTask = resource -> {
            uses.incrementAndGet();
            taskStarted.countDown();
            await(finishTask);
        };
        assertTrue(manager.offer(Stream.VIEW, () -> new TestResource(1), blockingTask, failures::add));
        Thread drainThread = Thread.ofPlatform().start(() -> manager.drain(1));
        await(taskStarted);

        manager.close();
        assertEquals(0, releases.get());
        assertEquals(1, manager.runningLeaseCount());
        finishTask.countDown();
        join(drainThread);

        assertEquals(1, uses.get());
        assertTrue(failures.isEmpty());
        assertEquals(1, releases.get());
        assertEquals(0, manager.runningLeaseCount());
    }

    @Test
    void framePublishedWhileEarlierFrameRunsDoesNotNeedFullRecovery() {
        AtomicInteger releases = new AtomicInteger();
        CountDownLatch taskStarted = new CountDownLatch(1);
        CountDownLatch finishTask = new CountDownLatch(1);
        List<TestResource> laterUses = new ArrayList<>();
        List<Throwable> failures = new ArrayList<>();
        AsyncResourceLeaseManager<Stream, TestResource> manager = newManager(releases);

        Consumer<TestResource> blockingTask = resource -> {
            taskStarted.countDown();
            await(finishTask);
        };
        assertTrue(manager.offer(Stream.VIEW, () -> new TestResource(1), blockingTask, failures::add));
        Thread drainThread = Thread.ofPlatform().start(() -> manager.drain(1));
        await(taskStarted);
        assertTrue(manager.offer(Stream.VIEW, () -> new TestResource(2), laterUses::add, failure -> {}));
        finishTask.countDown();
        join(drainThread);

        manager.drain(1);
        assertTrue(failures.isEmpty());
        assertFalse(laterUses.getFirst().fullResync());
        assertEquals(2, releases.get());
    }

    @Test
    void concurrentProducersKeepNativeResourceCountBounded() {
        int producerCount = 32;
        AtomicInteger liveResources = new AtomicInteger();
        AtomicInteger peakResources = new AtomicInteger();
        AtomicInteger releases = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
        Consumer<TestResource> releaser = resource -> {
            releases.incrementAndGet();
            liveResources.decrementAndGet();
        };
        AsyncResourceLeaseManager<Stream, TestResource> manager = new AsyncResourceLeaseManager<>(releaser, TestResource::requireFullResync, 2);

        for (int index = 0; index < producerCount; index++) {
            int id = index;
            Supplier<TestResource> resourceFactory = () -> {
                int live = liveResources.incrementAndGet();
                peakResources.accumulateAndGet(live, Math::max);
                return new TestResource(id);
            };
            Runnable producer = () -> {
                try {
                    await(start);
                    Stream stream = id % 2 == 0 ? Stream.VIEW : Stream.POPUP;
                    manager.offer(stream, resourceFactory, resource -> {}, failures::add);
                } catch (Throwable failure) {
                    failures.add(failure);
                }
            };
            threads.add(Thread.ofPlatform().start(producer));
        }

        start.countDown();
        threads.forEach(AsyncResourceLeaseManagerTest::join);
        assertTrue(failures.isEmpty());
        assertTrue(peakResources.get() <= 3, "One candidate plus one pending resource per stream is the hard producer bound");
        assertEquals(2, manager.pendingLeaseCount());
        manager.close();
        assertEquals(0, liveResources.get());
        assertEquals(producerCount, releases.get());
    }

    @Test
    void taskAndReleaseFailuresPreserveTaskFailureAsPrimary() {
        IllegalStateException taskFailure = new IllegalStateException("task failed");
        IllegalArgumentException releaseFailure = new IllegalArgumentException("release failed");
        List<Throwable> failures = new ArrayList<>();
        Consumer<TestResource> failingReleaser = resource -> { throw releaseFailure; };
        AsyncResourceLeaseManager<Stream, TestResource> manager = new AsyncResourceLeaseManager<>(failingReleaser, TestResource::requireFullResync, 2);

        Consumer<TestResource> failingTask = resource -> { throw taskFailure; };
        assertTrue(manager.offer(Stream.VIEW, () -> new TestResource(1), failingTask, failures::add));
        manager.drain(1);

        assertEquals(1, failures.size());
        assertSame(taskFailure, failures.getFirst());
        assertEquals(1, taskFailure.getSuppressed().length);
        assertSame(releaseFailure, taskFailure.getSuppressed()[0]);
        assertEquals(0, manager.pendingLeaseCount());
        assertEquals(0, manager.runningLeaseCount());
    }

    @Test
    void successfulTaskReportsReleaseFailure() {
        IllegalStateException releaseFailure = new IllegalStateException("release failed");
        List<Throwable> failures = new ArrayList<>();
        AtomicInteger uses = new AtomicInteger();
        Consumer<TestResource> failingReleaser = resource -> { throw releaseFailure; };
        AsyncResourceLeaseManager<Stream, TestResource> manager = new AsyncResourceLeaseManager<>(failingReleaser, TestResource::requireFullResync, 2);

        assertTrue(manager.offer(Stream.VIEW, () -> new TestResource(1), resource -> uses.incrementAndGet(), failures::add));
        manager.drain(1);

        assertEquals(1, uses.get());
        assertEquals(List.of(releaseFailure), failures);
        assertEquals(0, manager.runningLeaseCount());
    }

    @Test
    void closeAttemptsEveryReleaseAndIsIdempotentWhenReleasersFail() {
        List<Integer> releaseAttempts = new ArrayList<>();
        List<Throwable> failures = new ArrayList<>();
        IllegalStateException firstReleaseFailure = new IllegalStateException("first release failed");
        Consumer<TestResource> releaser = resource -> {
            releaseAttempts.add(resource.id());
            if (resource.id() == 1) {
                throw firstReleaseFailure;
            }
        };
        AsyncResourceLeaseManager<Stream, TestResource> manager = new AsyncResourceLeaseManager<>(releaser, TestResource::requireFullResync, 2);

        assertTrue(manager.offer(Stream.VIEW, () -> new TestResource(1), resource -> {}, failures::add));
        assertTrue(manager.offer(Stream.POPUP, () -> new TestResource(2), resource -> {}, failures::add));
        assertDoesNotThrow(manager::close);
        assertDoesNotThrow(manager::close);

        assertEquals(2, releaseAttempts.size());
        assertTrue(releaseAttempts.containsAll(List.of(1, 2)));
        assertEquals(List.of(firstReleaseFailure), failures);
        assertFalse(manager.isAccepting());
        assertEquals(0, manager.pendingLeaseCount());
    }

    @Test
    void concurrentCloseIsIdempotent() {
        AtomicInteger releases = new AtomicInteger();
        AsyncResourceLeaseManager<Stream, TestResource> manager = newManager(releases);
        assertTrue(manager.offer(Stream.VIEW, () -> new TestResource(1), resource -> {}, failure -> {}));
        assertTrue(manager.offer(Stream.POPUP, () -> new TestResource(2), resource -> {}, failure -> {}));
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> closers = new ArrayList<>();
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());

        for (int index = 0; index < 16; index++) {
            Runnable closer = () -> {
                try {
                    await(start);
                    manager.close();
                } catch (Throwable failure) {
                    failures.add(failure);
                }
            };
            closers.add(Thread.ofPlatform().start(closer));
        }
        start.countDown();
        closers.forEach(AsyncResourceLeaseManagerTest::join);

        assertTrue(failures.isEmpty());
        assertEquals(2, releases.get());
        assertEquals(0, manager.pendingLeaseCount());
        assertFalse(manager.isAccepting());
    }

    @Test
    void assertionFailureInsideTaskIsCapturedInsteadOfSwallowed() {
        AssertionError sentinel = new AssertionError("sentinel");
        List<Throwable> failures = new ArrayList<>();
        AtomicInteger releases = new AtomicInteger();
        AsyncResourceLeaseManager<Stream, TestResource> manager = newManager(releases);

        Consumer<TestResource> failingTask = resource -> { throw sentinel; };
        assertTrue(manager.offer(Stream.VIEW, () -> new TestResource(1), failingTask, failures::add));
        manager.drain(1);

        assertEquals(1, failures.size());
        assertSame(sentinel, failures.getFirst());
        assertEquals(1, releases.get());
    }

    @Test
    void throwingFailureHandlerCannotCorruptOwnershipState() {
        AtomicInteger releases = new AtomicInteger();
        IllegalStateException taskFailure = new IllegalStateException("task failed");
        AsyncResourceLeaseManager<Stream, TestResource> manager = newManager(releases);

        Consumer<TestResource> failingTask = resource -> { throw taskFailure; };
        Consumer<Throwable> failingHandler = failure -> { throw new IllegalArgumentException("handler failed"); };
        assertTrue(manager.offer(Stream.VIEW, () -> new TestResource(1), failingTask, failingHandler));
        assertDoesNotThrow(() -> manager.drain(1));

        assertEquals(1, releases.get());
        assertEquals(0, manager.pendingLeaseCount());
        assertEquals(0, manager.runningLeaseCount());
        assertEquals(1, taskFailure.getSuppressed().length);
    }

    private static AsyncResourceLeaseManager<Stream, TestResource> newManager(AtomicInteger releases) {
        return new AsyncResourceLeaseManager<>(resource -> releases.incrementAndGet(), TestResource::requireFullResync, 2);
    }

    private static List<Integer> ids(List<TestResource> resources) {
        return resources.stream().map(TestResource::id).toList();
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS), "Timed out waiting for test coordination");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for test coordination", failure);
        }
    }

    private static void join(Thread thread) {
        try {
            thread.join(5000L);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while joining test thread", failure);
        }
        assertFalse(thread.isAlive(), "Timed out waiting for test thread");
    }

    private enum Stream {
        VIEW,
        POPUP,
        EXTRA
    }

    private static final class TestResource {
        private final int id;
        private boolean fullResync;

        private TestResource(int id) {
            this.id = id;
        }

        private int id() {
            return id;
        }

        private void requireFullResync() {
            fullResync = true;
        }

        private boolean fullResync() {
            return fullResync;
        }
    }
}
