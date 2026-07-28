package de.keksuccino.mcef;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RenderThreadMailboxCoordinatorTest {
    @Test
    void registrationIsIdempotentAndRemovalStopsPumping() {
        RenderThreadMailboxCoordinator<String> coordinator = new RenderThreadMailboxCoordinator<>();
        List<String> pumped = new ArrayList<>();

        assertTrue(coordinator.register("browser"));
        assertTrue(coordinator.register("browser"));
        assertEquals(1, coordinator.registrationCount());
        coordinator.pump(pumped::add, (browser, failure) -> {});
        coordinator.unregister("browser");
        coordinator.pump(pumped::add, (browser, failure) -> {});

        assertEquals(List.of("browser"), pumped);
        assertEquals(0, coordinator.registrationCount());
    }

    @Test
    void browserFailureCannotBlockOtherBrowsers() {
        RenderThreadMailboxCoordinator<String> coordinator = new RenderThreadMailboxCoordinator<>();
        IllegalStateException failure = new IllegalStateException("broken browser");
        List<String> pumped = new ArrayList<>();
        List<Throwable> failures = new ArrayList<>();
        coordinator.register("broken");
        coordinator.register("healthy");

        Consumer<String> pump = browser -> {
            if (browser.equals("broken")) {
                throw failure;
            }
            pumped.add(browser);
        };
        coordinator.pump(pump, (browser, browserFailure) -> failures.add(browserFailure));

        assertEquals(List.of("healthy"), pumped);
        assertEquals(1, failures.size());
        assertSame(failure, failures.getFirst());
    }

    @Test
    void shutdownProcessesEveryBrowserOnceAndRejectsLaterRegistration() {
        RenderThreadMailboxCoordinator<String> coordinator = new RenderThreadMailboxCoordinator<>();
        List<String> shutdown = new ArrayList<>();
        coordinator.register("first");
        coordinator.register("second");

        coordinator.shutdown(shutdown::add, (browser, failure) -> {});
        coordinator.shutdown(shutdown::add, (browser, failure) -> {});

        assertEquals(List.of("first", "second"), shutdown);
        assertTrue(coordinator.isShutdown());
        assertEquals(0, coordinator.registrationCount());
        assertFalse(coordinator.register("late"));
    }

    @Test
    void shutdownFailureIsIsolatedAndRemainingBrowsersStillClose() {
        RenderThreadMailboxCoordinator<String> coordinator = new RenderThreadMailboxCoordinator<>();
        List<String> shutdown = new ArrayList<>();
        List<Throwable> failures = new ArrayList<>();
        IllegalStateException failure = new IllegalStateException("cleanup failed");
        coordinator.register("broken");
        coordinator.register("healthy");

        Consumer<String> close = browser -> {
            shutdown.add(browser);
            if (browser.equals("broken")) {
                throw failure;
            }
        };
        coordinator.shutdown(close, (browser, browserFailure) -> failures.add(browserFailure));

        assertEquals(List.of("broken", "healthy"), shutdown);
        assertEquals(List.of(failure), failures);
    }

    @Test
    void concurrentRegistrationAndRemovalRemainConsistent() {
        int registrationCount = 32;
        RenderThreadMailboxCoordinator<Integer> coordinator = new RenderThreadMailboxCoordinator<>();
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());

        for (int index = 0; index < registrationCount; index++) {
            int registration = index;
            Runnable registrationTask = () -> {
                try {
                    await(start);
                    coordinator.register(registration);
                    if (registration % 2 == 0) {
                        coordinator.unregister(registration);
                    }
                } catch (Throwable failure) {
                    failures.add(failure);
                }
            };
            threads.add(Thread.ofPlatform().start(registrationTask));
        }

        start.countDown();
        threads.forEach(RenderThreadMailboxCoordinatorTest::join);
        List<Integer> pumped = Collections.synchronizedList(new ArrayList<>());
        coordinator.pump(pumped::add, (registration, failure) -> {});

        assertTrue(failures.isEmpty());
        assertEquals(registrationCount / 2, coordinator.registrationCount());
        assertEquals(registrationCount / 2, pumped.size());
        assertTrue(pumped.stream().allMatch(registration -> registration % 2 == 1));
    }

    @Test
    void shutdownDrainsQueuedMailboxesWithoutExecutorCompletionAndPreventsNewAllocation() {
        RenderThreadMailboxCoordinator<BrowserState> coordinator = new RenderThreadMailboxCoordinator<>();
        BrowserState first = new BrowserState();
        BrowserState second = new BrowserState();
        coordinator.register(first);
        coordinator.register(second);
        first.offer(1);
        second.offer(2);

        coordinator.shutdown(BrowserState::close, (browser, failure) -> browser.failures.add(failure));

        assertEquals(1, first.releases.get());
        assertEquals(1, second.releases.get());
        assertEquals(1, first.cleanupCalls.get());
        assertEquals(1, second.cleanupCalls.get());
        assertEquals(1, first.nativeCloseCalls.get());
        assertEquals(1, second.nativeCloseCalls.get());
        assertEquals(0, first.manager.pendingLeaseCount());
        assertEquals(0, second.manager.pendingLeaseCount());
        assertFalse(first.offer(3));
        assertFalse(second.offer(4));
        assertEquals(1, first.allocations.get());
        assertEquals(1, second.allocations.get());
    }

    @Test
    void framePumpConsumesBoundedLatestFramesWithoutExecutorTasks() {
        RenderThreadMailboxCoordinator<BrowserState> coordinator = new RenderThreadMailboxCoordinator<>();
        BrowserState browser = new BrowserState();
        coordinator.register(browser);
        browser.offer(1);
        browser.offer(2);

        coordinator.pump(BrowserState::pump, (state, failure) -> state.failures.add(failure));

        assertEquals(List.of(2), browser.used);
        assertEquals(2, browser.releases.get());
        assertEquals(0, browser.manager.pendingLeaseCount());
        assertTrue(browser.failures.isEmpty());
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

    private static final class BrowserState {
        private final AtomicInteger allocations = new AtomicInteger();
        private final AtomicInteger releases = new AtomicInteger();
        private final AtomicInteger cleanupCalls = new AtomicInteger();
        private final AtomicInteger nativeCloseCalls = new AtomicInteger();
        private final List<Integer> used = new ArrayList<>();
        private final List<Throwable> failures = new ArrayList<>();
        private final AsyncResourceLeaseManager<String, Resource> manager = new AsyncResourceLeaseManager<>(resource -> releases.incrementAndGet(), Resource::requireFullResync, 1);

        private boolean offer(int id) {
            Supplier<Resource> resourceFactory = () -> {
                allocations.incrementAndGet();
                return new Resource(id);
            };
            return manager.offer("view", resourceFactory, resource -> used.add(resource.id), failures::add);
        }

        private void pump() {
            manager.drain(1);
        }

        private void close() {
            manager.close();
            cleanupCalls.incrementAndGet();
            nativeCloseCalls.incrementAndGet();
        }
    }

    private static final class Resource {
        private final int id;
        private boolean fullResync;

        private Resource(int id) {
            this.id = id;
        }

        private void requireFullResync() {
            fullResync = true;
        }
    }
}
