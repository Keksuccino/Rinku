package de.keksuccino.rinku.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class CefShutdownTest {

    private static final long MILLISECOND = TimeUnit.MILLISECONDS.toNanos(1);

    @AfterEach
    void clearInterrupt() {
        Thread.interrupted();
    }

    @Test
    void completedShutdownDoesNotPumpOrWait() {
        assertTrue(CefUtil.awaitTermination(() -> true, () -> fail("Unexpected event pump"), () -> 0L, nanos -> fail("Unexpected wait"), MILLISECOND));
    }

    @Test
    void servicesMainThreadCallbacksUntilNativeShutdownCompletes() {
        AtomicInteger callbacks = new AtomicInteger();
        AtomicLong clock = new AtomicLong();
        assertTrue(CefUtil.awaitTermination(() -> callbacks.get() == 3, callbacks::incrementAndGet, clock::get, clock::addAndGet, 10 * MILLISECOND));
        assertEquals(3, callbacks.get());
        assertEquals(2 * MILLISECOND, clock.get());
    }

    @Test
    void asynchronousCompletionDuringPauseStopsFurtherPumping() {
        AtomicBoolean complete = new AtomicBoolean();
        AtomicInteger callbacks = new AtomicInteger();
        assertTrue(CefUtil.awaitTermination(complete::get, callbacks::incrementAndGet, () -> 0L, nanos -> complete.set(true), MILLISECOND));
        assertEquals(1, callbacks.get());
    }

    @Test
    void incompleteShutdownUsesBoundedPausesAndTimesOut() {
        AtomicLong clock = new AtomicLong();
        AtomicInteger callbacks = new AtomicInteger();
        long timeout = 2 * MILLISECOND + 1;
        assertFalse(CefUtil.awaitTermination(() -> false, callbacks::incrementAndGet, clock::get, nanos -> {
            assertTrue(nanos > 0 && nanos <= MILLISECOND);
            clock.addAndGet(nanos);
        }, timeout));
        assertEquals(timeout, clock.get());
        assertEquals(3, callbacks.get());
    }

    @Test
    void timeSpentPumpingCountsTowardsTimeout() {
        AtomicLong clock = new AtomicLong();
        assertFalse(CefUtil.awaitTermination(() -> false, () -> clock.addAndGet(MILLISECOND), clock::get, nanos -> fail("Deadline already reached"), MILLISECOND));
    }

    @Test
    void zeroTimeoutDoesNotPump() {
        assertFalse(CefUtil.awaitTermination(() -> false, () -> fail("Deadline already reached"), () -> 0L, nanos -> fail("Unexpected wait"), 0));
    }

    @Test
    void elapsedTimeHandlesNanoTimeWrapping() {
        AtomicLong clock = new AtomicLong(Long.MAX_VALUE - MILLISECOND);
        assertFalse(CefUtil.awaitTermination(() -> false, () -> {}, clock::get, clock::addAndGet, 3 * MILLISECOND));
        assertEquals(Long.MIN_VALUE + 2 * MILLISECOND - 1, clock.get());
    }

    @Test
    void interruptionDoesNotAbandonNativeCleanupOrSpin() {
        Thread.currentThread().interrupt();
        AtomicLong clock = new AtomicLong();
        AtomicInteger pauses = new AtomicInteger();
        assertTrue(CefUtil.awaitTermination(() -> pauses.get() == 2, () -> {}, clock::get, nanos -> {
            assertFalse(Thread.currentThread().isInterrupted());
            clock.addAndGet(nanos);
            pauses.incrementAndGet();
            Thread.currentThread().interrupt();
        }, 10 * MILLISECOND));
        assertTrue(Thread.currentThread().isInterrupted());
    }

    @Test
    void pumpFailurePropagatesAndRestoresInterrupt() {
        RuntimeException failure = new RuntimeException();
        Thread.currentThread().interrupt();
        assertSame(failure, assertThrows(RuntimeException.class, () -> CefUtil.awaitTermination(() -> false, () -> {
            throw failure;
        }, () -> 0L, nanos -> fail("Unexpected wait"), MILLISECOND)));
        assertTrue(Thread.currentThread().isInterrupted());
    }

}
