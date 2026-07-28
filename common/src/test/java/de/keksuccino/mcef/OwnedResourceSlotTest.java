package de.keksuccino.mcef;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OwnedResourceSlotTest {
    @Test
    void replacementPublishesNewResourceBeforeDisposingOldResource() {
        AtomicReference<OwnedResourceSlot<TestResource>> slotRef = new AtomicReference<>();
        AtomicReference<TestResource> currentSeenDuringDisposal = new AtomicReference<>();
        TestResource first = new TestResource(1);
        TestResource second = new TestResource(2);
        OwnedResourceSlot<TestResource> slot = new OwnedResourceSlot<>(resource -> {
            currentSeenDuringDisposal.set(slotRef.get().get());
            resource.dispose();
        });
        slotRef.set(slot);

        assertTrue(slot.replace(first));
        assertTrue(slot.replace(second));

        assertSame(second, currentSeenDuringDisposal.get());
        assertEquals(1, first.disposals());
        assertEquals(0, second.disposals());
        assertTrue(slot.reset());
        assertFalse(slot.reset());
        assertEquals(1, second.disposals());
    }

    @Test
    void sameUnderlyingResourceRepublishesMetadataWithoutDuplicatingOwnership() {
        TestResource resource = new TestResource(1);
        ResourceView first = new ResourceView(resource, 10);
        ResourceView second = new ResourceView(resource, 20);
        OwnedResourceSlot<ResourceView> slot = new OwnedResourceSlot<>(view -> view.resource.dispose(), view -> view.resource);

        assertTrue(slot.replace(first));
        assertTrue(slot.replace(second));

        assertSame(second, slot.get());
        assertEquals(0, resource.disposals());
        assertTrue(slot.reset());
        assertEquals(1, resource.disposals());
    }

    @Test
    void clearAndUseDetachesBeforeActionAndDisposesAfterAction() {
        TestResource resource = new TestResource(1);
        OwnedResourceSlot<TestResource> slot = new OwnedResourceSlot<>(TestResource::dispose);
        assertTrue(slot.replace(resource));

        assertTrue(slot.clearAndUse(current -> {
            assertSame(resource, current);
            assertNull(slot.get());
            assertEquals(0, resource.disposals());
        }));

        assertEquals(1, resource.disposals());
        assertFalse(slot.clearAndUse(current -> {}));
    }

    @Test
    void concurrentResetDefersDisposalUntilUseReturnsWithoutHoldingLock() {
        TestResource resource = new TestResource(1);
        OwnedResourceSlot<TestResource> slot = new OwnedResourceSlot<>(TestResource::dispose);
        CountDownLatch useStarted = new CountDownLatch(1);
        CountDownLatch finishUse = new CountDownLatch(1);
        CountDownLatch resetFinished = new CountDownLatch(1);
        assertTrue(slot.replace(resource));

        Thread useThread = Thread.ofPlatform().start(() -> slot.useIfCurrent(resource, current -> {
            useStarted.countDown();
            await(finishUse);
        }));
        await(useStarted);
        Thread resetThread = Thread.ofPlatform().start(() -> {
            assertTrue(slot.reset());
            resetFinished.countDown();
        });

        await(resetFinished);
        assertNull(slot.get());
        assertEquals(0, resource.disposals());
        finishUse.countDown();
        join(useThread);
        join(resetThread);
        assertEquals(1, resource.disposals());
    }

    @Test
    void replacementDuringUseDefersOnlyRetiredResourceDisposal() {
        TestResource first = new TestResource(1);
        TestResource second = new TestResource(2);
        OwnedResourceSlot<TestResource> slot = new OwnedResourceSlot<>(TestResource::dispose);
        CountDownLatch useStarted = new CountDownLatch(1);
        CountDownLatch finishUse = new CountDownLatch(1);
        assertTrue(slot.replace(first));

        Thread useThread = Thread.ofPlatform().start(() -> slot.useIfCurrent(first, current -> {
            useStarted.countDown();
            await(finishUse);
        }));
        await(useStarted);
        assertTrue(slot.replace(second));

        assertSame(second, slot.get());
        assertEquals(0, first.disposals());
        assertEquals(0, second.disposals());
        finishUse.countDown();
        join(useThread);
        assertEquals(1, first.disposals());
        assertEquals(0, second.disposals());
        assertTrue(slot.reset());
        assertEquals(1, second.disposals());
    }

    @Test
    void failedProvisionalUseReturnsOwnershipToCallerWithoutDisposal() {
        TestResource resource = new TestResource(1);
        OwnedResourceSlot<TestResource> slot = new OwnedResourceSlot<>(TestResource::dispose);
        IllegalStateException failure = new IllegalStateException("native call failed");
        assertTrue(slot.replace(resource));

        IllegalStateException caught = assertThrows(IllegalStateException.class, () -> slot.useIfCurrentAndAbandonOnFailure(resource, current -> { throw failure; }));

        assertSame(failure, caught);
        assertNull(slot.get());
        assertEquals(0, resource.disposals());
        resource.dispose();
        assertEquals(1, resource.disposals());
        assertFalse(slot.reset());
    }

    @Test
    void terminalCloseIsIdempotentAndDisposesLateReplacement() {
        TestResource current = new TestResource(1);
        TestResource late = new TestResource(2);
        OwnedResourceSlot<TestResource> slot = new OwnedResourceSlot<>(TestResource::dispose);
        assertTrue(slot.replace(current));

        slot.close();
        slot.close();

        assertTrue(slot.isClosed());
        assertNull(slot.get());
        assertEquals(1, current.disposals());
        assertFalse(slot.replace(late));
        assertNull(slot.get());
        assertEquals(1, late.disposals());
    }

    @Test
    void terminalCloseDuringUseSealsImmediatelyAndDefersCurrentDisposal() {
        TestResource current = new TestResource(1);
        TestResource late = new TestResource(2);
        OwnedResourceSlot<TestResource> slot = new OwnedResourceSlot<>(TestResource::dispose);
        CountDownLatch useStarted = new CountDownLatch(1);
        CountDownLatch finishUse = new CountDownLatch(1);
        CountDownLatch closeFinished = new CountDownLatch(1);
        assertTrue(slot.replace(current));

        Thread useThread = Thread.ofPlatform().start(() -> slot.useIfCurrent(current, resource -> {
            useStarted.countDown();
            await(finishUse);
        }));
        await(useStarted);
        Thread closeThread = Thread.ofPlatform().start(() -> {
            slot.close();
            closeFinished.countDown();
        });

        await(closeFinished);
        assertTrue(slot.isClosed());
        assertNull(slot.get());
        assertEquals(0, current.disposals());
        assertFalse(slot.replace(late));
        assertEquals(1, late.disposals());
        finishUse.countDown();
        join(useThread);
        join(closeThread);
        assertEquals(1, current.disposals());
    }

    @Test
    void concurrentResetDisposesOwnedResourceExactlyOnce() {
        TestResource resource = new TestResource(1);
        OwnedResourceSlot<TestResource> slot = new OwnedResourceSlot<>(TestResource::dispose);
        CountDownLatch start = new CountDownLatch(1);
        List<Boolean> results = Collections.synchronizedList(new ArrayList<>());
        List<Thread> threads = new ArrayList<>();
        assertTrue(slot.replace(resource));

        for (int index = 0; index < 32; index++) {
            threads.add(Thread.ofPlatform().start(() -> {
                await(start);
                results.add(slot.reset());
            }));
        }
        start.countDown();
        threads.forEach(OwnedResourceSlotTest::join);

        assertEquals(1, results.stream().filter(Boolean::booleanValue).count());
        assertEquals(1, resource.disposals());
    }

    @Test
    void disposerCanWaitForAnotherThreadToReenterSlot() {
        TestResource first = new TestResource(1);
        TestResource replacement = new TestResource(2);
        AtomicReference<OwnedResourceSlot<TestResource>> slotRef = new AtomicReference<>();
        AtomicBoolean reentered = new AtomicBoolean();
        OwnedResourceSlot<TestResource> slot = new OwnedResourceSlot<>(resource -> {
            resource.dispose();
            if (resource != first) return;

            Thread replacementThread = Thread.ofPlatform().start(() -> reentered.set(slotRef.get().replace(replacement)));
            join(replacementThread);
        });
        slotRef.set(slot);
        assertTrue(slot.replace(first));

        assertTrue(slot.reset());

        assertTrue(reentered.get());
        assertSame(replacement, slot.get());
        assertEquals(1, first.disposals());
        assertTrue(slot.reset());
        assertEquals(1, replacement.disposals());
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

    private static final class TestResource {
        private final int id;
        private final AtomicInteger disposals = new AtomicInteger();

        private TestResource(int id) {
            this.id = id;
        }

        private void dispose() {
            disposals.incrementAndGet();
        }

        private int disposals() {
            return disposals.get();
        }

        @Override
        public String toString() {
            return "TestResource[" + id + "]";
        }
    }

    private record ResourceView(TestResource resource, int metadata) {}
}
