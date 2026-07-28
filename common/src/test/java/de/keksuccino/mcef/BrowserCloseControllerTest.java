package de.keksuccino.mcef;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrowserCloseControllerTest {
    @Test
    void closeActionsAreIndependentAndIdempotent() {
        BrowserCloseController controller = new BrowserCloseController();
        AtomicInteger stopAdmissionCalls = new AtomicInteger();
        AtomicInteger nativeCloseCalls = new AtomicInteger();

        assertTrue(controller.requestClose(stopAdmissionCalls::incrementAndGet));
        assertFalse(controller.requestClose(stopAdmissionCalls::incrementAndGet));
        assertTrue(controller.closeNative(nativeCloseCalls::incrementAndGet));
        assertFalse(controller.closeNative(nativeCloseCalls::incrementAndGet));

        assertTrue(controller.isCloseRequested());
        assertEquals(1, stopAdmissionCalls.get());
        assertEquals(1, nativeCloseCalls.get());
    }

    @Test
    void concurrentCloseAttemptsRunEachActionOnce() {
        BrowserCloseController controller = new BrowserCloseController();
        AtomicInteger stopAdmissionCalls = new AtomicInteger();
        AtomicInteger nativeCloseCalls = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        List<Throwable> failures = new ArrayList<>();

        for (int index = 0; index < 32; index++) {
            Runnable closer = () -> {
                try {
                    await(start);
                    controller.requestClose(stopAdmissionCalls::incrementAndGet);
                    controller.closeNative(nativeCloseCalls::incrementAndGet);
                } catch (Throwable failure) {
                    synchronized (failures) {
                        failures.add(failure);
                    }
                }
            };
            threads.add(Thread.ofPlatform().start(closer));
        }

        start.countDown();
        threads.forEach(BrowserCloseControllerTest::join);
        assertTrue(failures.isEmpty());
        assertEquals(1, stopAdmissionCalls.get());
        assertEquals(1, nativeCloseCalls.get());
    }

    @Test
    void nativeCloseStillRunsAfterAdmissionShutdownFailure() {
        BrowserCloseController controller = new BrowserCloseController();
        AtomicInteger nativeCloseCalls = new AtomicInteger();
        IllegalStateException failure = new IllegalStateException("admission shutdown failed");

        Runnable failingAdmissionShutdown = () -> { throw failure; };
        IllegalStateException caught = assertThrows(IllegalStateException.class, () -> controller.requestClose(failingAdmissionShutdown));
        controller.closeNative(nativeCloseCalls::incrementAndGet);

        assertSame(failure, caught);
        assertTrue(controller.isCloseRequested());
        assertEquals(1, nativeCloseCalls.get());
    }

    @Test
    void nativeCallbackClaimsCloseWithoutIssuingAnotherNativeRequest() {
        BrowserCloseController controller = new BrowserCloseController();
        AtomicInteger nativeCloseCalls = new AtomicInteger();

        assertTrue(controller.markNativeClosed());
        assertFalse(controller.markNativeClosed());
        assertFalse(controller.closeNative(nativeCloseCalls::incrementAndGet));
        assertEquals(0, nativeCloseCalls.get());
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
}
