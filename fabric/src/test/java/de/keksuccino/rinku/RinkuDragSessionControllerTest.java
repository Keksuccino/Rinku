package de.keksuccino.rinku;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RinkuDragSessionControllerTest {
    private static final int NO_CURSOR = -1;
    private static final int COPY_OPERATION = 1;
    private static final int MOVE_OPERATION = 16;
    private static final int COPY_CURSOR = 36;
    private static final int MOVE_CURSOR = 29;

    @Test
    void successfulDropUsesTargetThenSourceOrderAndLatestOperation() {
        List<String> events = new ArrayList<>();
        RinkuDragSessionController<TestDrag> controller = controller(events);
        RecordingCallbacks callbacks = new RecordingCallbacks(events);
        TestDrag drag = new TestDrag("drag");

        assertTrue(controller.start(drag, COPY_OPERATION | MOVE_OPERATION, 4, 7, 0x10, callbacks));
        assertTrue(controller.updateOperation(MOVE_OPERATION, MOVE_CURSOR));
        assertEquals(MOVE_CURSOR, controller.virtualCursor(99));
        assertTrue(controller.finish(20, 30, 0, callbacks));

        assertEquals(List.of("enter:drag:4:7:16:17", "drop:20:30:0", "sourceEnded:20:30:16", "sourceSystemEnded", "dispose:drag"), events);
        assertEquals(1, drag.disposals());
        assertFalse(controller.isDragging());
        assertFalse(controller.finish(20, 30, 0, callbacks));
        assertFalse(events.stream().anyMatch(event -> event.equals("leave")));
    }

    @Test
    void cancellationUsesImmediateSystemEndedPathAndIsIdempotent() {
        List<String> events = new ArrayList<>();
        RinkuDragSessionController<TestDrag> controller = controller(events);
        RecordingCallbacks callbacks = new RecordingCallbacks(events);
        TestDrag drag = new TestDrag("drag");

        assertTrue(controller.start(drag, COPY_OPERATION, 1, 2, 0x10, callbacks));
        assertTrue(controller.cancel(callbacks));
        assertFalse(controller.cancel(callbacks));

        assertEquals(List.of("enter:drag:1:2:16:1", "leave", "sourceSystemEnded", "dispose:drag"), events);
        assertEquals(1, drag.disposals());
        assertFalse(events.stream().anyMatch(event -> event.startsWith("sourceEnded:")));
    }

    @Test
    void normalFalseDisposesDelegatedCloneAndNeverSendsSourceCallbacks() {
        List<String> events = new ArrayList<>();
        RinkuDragSessionController<TestDrag> controller = controller(events);
        RecordingCallbacks callbacks = new RecordingCallbacks(events);
        TestDrag delegatedClone = new TestDrag("clone");
        controller.close(callbacks);

        // CefClient transfers its clone after any normal handler return, including false. The
        // rejecting handler must therefore dispose it and must not issue source completion.
        assertFalse(controller.start(delegatedClone, COPY_OPERATION, 1, 2, 0x10, callbacks));

        assertEquals(List.of("dispose:clone"), events);
        assertEquals(1, delegatedClone.disposals());
        assertFalse(events.stream().anyMatch(event -> event.startsWith("source")));
    }

    @Test
    void exceptionalStartLeavesCloneForDelegatorAndNeverSendsSourceCallbacks() {
        List<String> events = new ArrayList<>();
        RinkuDragSessionController<TestDrag> controller = controller(events);
        RecordingCallbacks callbacks = new RecordingCallbacks(events);
        TestDrag delegatedClone = new TestDrag("clone");
        IllegalStateException enterFailure = new IllegalStateException("enter failed");
        callbacks.targetEnterHook = () -> { throw enterFailure; };

        IllegalStateException caught = assertThrows(IllegalStateException.class, () -> controller.start(delegatedClone, COPY_OPERATION, 1, 2, 0x10, callbacks));
        assertSame(enterFailure, caught);
        assertEquals(0, delegatedClone.disposals());
        assertEquals(List.of("enter:clone:1:2:16:1", "leave"), events);
        assertFalse(events.stream().anyMatch(event -> event.startsWith("source")));

        // This is the matching CefClient exceptional path: ownership never transferred, so the
        // delegator releases the clone exactly once after the handler throws.
        delegatedClone.dispose();
        assertEquals(1, delegatedClone.disposals());
        assertFalse(controller.isDragging());
    }

    @Test
    void replacementCancelsAndDisposesOldSessionBeforeEnteringNewSession() {
        List<String> events = new ArrayList<>();
        RinkuDragSessionController<TestDrag> controller = controller(events);
        RecordingCallbacks callbacks = new RecordingCallbacks(events);
        TestDrag first = new TestDrag("first");
        TestDrag second = new TestDrag("second");

        assertTrue(controller.start(first, COPY_OPERATION, 1, 2, 0x10, callbacks));
        assertTrue(controller.updateOperation(MOVE_OPERATION, MOVE_CURSOR));
        assertTrue(controller.start(second, COPY_OPERATION, 3, 4, 0x10, callbacks));

        assertEquals(List.of("enter:first:1:2:16:1", "leave", "sourceSystemEnded", "dispose:first", "enter:second:3:4:16:1"), events);
        assertEquals(1, first.disposals());
        assertEquals(0, second.disposals());

        assertTrue(controller.finish(8, 9, 0, callbacks));
        assertEquals("sourceEnded:8:9:0", events.get(events.size() - 3));
        assertEquals(1, second.disposals());
    }

    @Test
    void closeCancelsCurrentSessionAndRejectsEveryLaterStart() {
        List<String> events = new ArrayList<>();
        RinkuDragSessionController<TestDrag> controller = controller(events);
        RecordingCallbacks callbacks = new RecordingCallbacks(events);
        TestDrag current = new TestDrag("current");
        TestDrag late = new TestDrag("late");

        assertTrue(controller.start(current, MOVE_OPERATION, 1, 2, 0x10, callbacks));
        controller.close(callbacks);
        controller.close(callbacks);
        assertFalse(controller.start(late, COPY_OPERATION, 3, 4, 0x10, callbacks));

        assertEquals(List.of("enter:current:1:2:16:16", "leave", "sourceSystemEnded", "dispose:current", "dispose:late"), events);
        assertEquals(1, current.disposals());
        assertEquals(1, late.disposals());
        assertFalse(controller.isDragging());
    }

    @Test
    void closeReenteredDuringTargetEnterMakesStartReturnFalseWithoutSourceCallbacks() {
        List<String> events = new ArrayList<>();
        RinkuDragSessionController<TestDrag> controller = controller(events);
        RecordingCallbacks callbacks = new RecordingCallbacks(events);
        TestDrag drag = new TestDrag("drag");
        callbacks.targetEnterHook = () -> controller.close(callbacks);

        assertFalse(controller.start(drag, COPY_OPERATION, 1, 2, 0x10, callbacks));

        assertEquals(List.of("enter:drag:1:2:16:1", "leave", "dispose:drag"), events);
        assertEquals(1, drag.disposals());
        assertFalse(events.stream().anyMatch(event -> event.startsWith("source")));
    }

    @Test
    void operationUpdateDuringTargetEnterBelongsToEnteringSession() {
        List<String> events = new ArrayList<>();
        RinkuDragSessionController<TestDrag> controller = controller(events);
        RecordingCallbacks callbacks = new RecordingCallbacks(events);
        TestDrag drag = new TestDrag("drag");
        callbacks.targetEnterHook = () -> assertTrue(controller.updateOperation(MOVE_OPERATION, MOVE_CURSOR));

        assertTrue(controller.start(drag, COPY_OPERATION | MOVE_OPERATION, 1, 2, 0x10, callbacks));
        assertEquals(MOVE_CURSOR, controller.virtualCursor(99));
        assertTrue(controller.finish(3, 4, 0, callbacks));

        assertTrue(events.contains("sourceEnded:3:4:16"));
    }

    @Test
    void operationAndCursorAreUpdatedAtomicallyForCurrentSession() {
        List<String> events = new ArrayList<>();
        RinkuDragSessionController<TestDrag> controller = controller(events);
        RecordingCallbacks callbacks = new RecordingCallbacks(events);

        assertTrue(controller.start(new TestDrag("drag"), COPY_OPERATION | MOVE_OPERATION, 1, 2, 0x10, callbacks));
        assertTrue(controller.updateOperation(COPY_OPERATION, COPY_CURSOR));
        assertEquals(COPY_CURSOR, controller.virtualCursor(77));
        assertFalse(controller.updateOperation(MOVE_OPERATION, COPY_CURSOR));
        assertEquals(COPY_CURSOR, controller.virtualCursor(77));
        assertTrue(controller.updateOperation(MOVE_OPERATION, NO_CURSOR));
        assertEquals(77, controller.virtualCursor(77));
        assertTrue(controller.finish(3, 4, 0, callbacks));

        assertTrue(events.contains("sourceEnded:3:4:16"));
    }

    @Test
    void finishCapturesOperationBeforeAConcurrentLateUpdate() {
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        RinkuDragSessionController<TestDrag> controller = controller(events);
        RecordingCallbacks callbacks = new RecordingCallbacks(events);
        CountDownLatch dropStarted = new CountDownLatch(1);
        CountDownLatch releaseDrop = new CountDownLatch(1);
        AtomicReference<Boolean> finishResult = new AtomicReference<>();
        AtomicReference<Boolean> updateResult = new AtomicReference<>();
        callbacks.targetDropHook = () -> {
            dropStarted.countDown();
            await(releaseDrop);
        };
        assertTrue(controller.start(new TestDrag("drag"), COPY_OPERATION | MOVE_OPERATION, 1, 2, 0x10, callbacks));
        assertTrue(controller.updateOperation(COPY_OPERATION, COPY_CURSOR));

        Thread finishThread = TestThreads.start(() -> finishResult.set(controller.finish(3, 4, 0, callbacks)));
        await(dropStarted);
        Thread updateThread = TestThreads.start(() -> updateResult.set(controller.updateOperation(MOVE_OPERATION, MOVE_CURSOR)));
        releaseDrop.countDown();
        join(finishThread);
        join(updateThread);

        assertEquals(Boolean.TRUE, finishResult.get());
        assertEquals(Boolean.FALSE, updateResult.get());
        assertTrue(events.contains("sourceEnded:3:4:1"));
        assertFalse(events.contains("sourceEnded:3:4:16"));
    }

    @Test
    void concurrentFinishAndCancelCompleteTheSessionExactlyOnce() {
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        RinkuDragSessionController<TestDrag> controller = controller(events);
        RecordingCallbacks callbacks = new RecordingCallbacks(events);
        TestDrag drag = new TestDrag("drag");
        CountDownLatch start = new CountDownLatch(1);
        List<Boolean> results = Collections.synchronizedList(new ArrayList<>());
        List<Thread> threads = new ArrayList<>();
        assertTrue(controller.start(drag, COPY_OPERATION, 1, 2, 0x10, callbacks));

        for (int index = 0; index < 32; index++) {
            boolean finish = index % 2 == 0;
            threads.add(TestThreads.start(() -> {
                await(start);
                results.add(finish ? controller.finish(3, 4, 0, callbacks) : controller.cancel(callbacks));
            }));
        }
        start.countDown();
        threads.forEach(RinkuDragSessionControllerTest::join);

        assertEquals(1, results.stream().filter(Boolean::booleanValue).count());
        assertEquals(1, drag.disposals());
        assertEquals(1, events.stream().filter(event -> event.equals("dispose:drag")).count());
        assertEquals(1, events.stream().filter(event -> event.equals("sourceSystemEnded")).count());
        assertFalse(controller.isDragging());
    }

    @Test
    void lifecycleCallbackFailuresStillCompleteDisposeAndPreserveOrder() {
        List<String> events = new ArrayList<>();
        IllegalStateException dropFailure = new IllegalStateException("drop failed");
        IllegalArgumentException endedFailure = new IllegalArgumentException("ended-at failed");
        UnsupportedOperationException systemFailure = new UnsupportedOperationException("system-ended failed");
        AssertionError disposalFailure = new AssertionError("dispose failed");
        RinkuDragSessionController<TestDrag> controller = new RinkuDragSessionController<>(resource -> {
            events.add("dispose:" + resource.name);
            resource.dispose();
            throw disposalFailure;
        }, NO_CURSOR);
        RecordingCallbacks callbacks = new RecordingCallbacks(events);
        callbacks.targetDropHook = () -> { throw dropFailure; };
        callbacks.sourceEndedHook = () -> { throw endedFailure; };
        callbacks.sourceSystemEndedHook = () -> { throw systemFailure; };
        TestDrag drag = new TestDrag("drag");
        assertTrue(controller.start(drag, COPY_OPERATION, 1, 2, 0x10, callbacks));

        IllegalStateException caught = assertThrows(IllegalStateException.class, () -> controller.finish(3, 4, 0, callbacks));

        assertSame(dropFailure, caught);
        assertArrayEquals(new Throwable[]{endedFailure, systemFailure, disposalFailure}, caught.getSuppressed());
        assertEquals(List.of("enter:drag:1:2:16:1", "drop:3:4:0", "sourceEnded:3:4:0", "sourceSystemEnded", "dispose:drag"), events);
        assertEquals(1, drag.disposals());
        assertFalse(controller.isDragging());
        assertFalse(controller.finish(3, 4, 0, callbacks));
    }

    @Test
    void reentrantLifecycleCallsCannotCompleteOrReplaceRetiringSession() {
        List<String> events = new ArrayList<>();
        RinkuDragSessionController<TestDrag> controller = controller(events);
        RecordingCallbacks callbacks = new RecordingCallbacks(events);
        TestDrag active = new TestDrag("active");
        TestDrag reentrant = new TestDrag("reentrant");
        AtomicReference<Boolean> nestedFinish = new AtomicReference<>();
        AtomicReference<Boolean> nestedCancel = new AtomicReference<>();
        AtomicReference<Boolean> nestedStart = new AtomicReference<>();
        callbacks.targetDropHook = () -> {
            nestedFinish.set(controller.finish(8, 9, 0, callbacks));
            nestedCancel.set(controller.cancel(callbacks));
            nestedStart.set(controller.start(reentrant, COPY_OPERATION, 5, 6, 0x10, callbacks));
        };
        assertTrue(controller.start(active, COPY_OPERATION, 1, 2, 0x10, callbacks));

        assertTrue(controller.finish(3, 4, 0, callbacks));

        assertEquals(Boolean.FALSE, nestedFinish.get());
        assertEquals(Boolean.FALSE, nestedCancel.get());
        assertEquals(Boolean.FALSE, nestedStart.get());
        assertEquals(1, active.disposals());
        assertEquals(1, reentrant.disposals());
        assertEquals(List.of("enter:active:1:2:16:1", "drop:3:4:0", "dispose:reentrant", "sourceEnded:3:4:0", "sourceSystemEnded", "dispose:active"), events);
    }

    @Test
    void nativeCallbackCanWaitForCrossThreadReentryWithoutDeadlock() {
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        RinkuDragSessionController<TestDrag> controller = controller(events);
        RecordingCallbacks callbacks = new RecordingCallbacks(events);
        TestDrag active = new TestDrag("active");
        TestDrag rejected = new TestDrag("rejected");
        AtomicReference<Boolean> nestedStart = new AtomicReference<>();
        callbacks.targetDropHook = () -> {
            Thread reentryThread = TestThreads.start(() -> nestedStart.set(controller.start(rejected, COPY_OPERATION, 5, 6, 0x10, callbacks)));
            join(reentryThread);
        };
        assertTrue(controller.start(active, COPY_OPERATION, 1, 2, 0x10, callbacks));

        assertTrue(controller.finish(3, 4, 0, callbacks));

        assertEquals(Boolean.FALSE, nestedStart.get());
        assertEquals(1, active.disposals());
        assertEquals(1, rejected.disposals());
        assertEquals(List.of("enter:active:1:2:16:1", "drop:3:4:0", "dispose:rejected", "sourceEnded:3:4:0", "sourceSystemEnded", "dispose:active"), events);
    }

    private static RinkuDragSessionController<TestDrag> controller(List<String> events) {
        return new RinkuDragSessionController<>(resource -> {
            events.add("dispose:" + resource.name);
            resource.dispose();
        }, NO_CURSOR);
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

    private static final class RecordingCallbacks implements RinkuDragSessionController.Callbacks<TestDrag> {
        private final List<String> events;
        private Runnable targetEnterHook = () -> {};
        private Runnable targetDropHook = () -> {};
        private Runnable sourceEndedHook = () -> {};
        private Runnable sourceSystemEndedHook = () -> {};

        private RecordingCallbacks(List<String> events) {
            this.events = events;
        }

        @Override
        public void targetEnter(TestDrag resource, int x, int y, int modifiers, int allowedOperations) {
            events.add("enter:" + resource.name + ":" + x + ":" + y + ":" + modifiers + ":" + allowedOperations);
            targetEnterHook.run();
        }

        @Override
        public void targetDrop(int x, int y, int modifiers) {
            events.add("drop:" + x + ":" + y + ":" + modifiers);
            targetDropHook.run();
        }

        @Override
        public void targetLeave() {
            events.add("leave");
        }

        @Override
        public void sourceEndedAt(int x, int y, int operation) {
            events.add("sourceEnded:" + x + ":" + y + ":" + operation);
            sourceEndedHook.run();
        }

        @Override
        public void sourceSystemDragEnded() {
            events.add("sourceSystemEnded");
            sourceSystemEndedHook.run();
        }
    }

    private static final class TestDrag {
        private final String name;
        private final AtomicInteger disposals = new AtomicInteger();

        private TestDrag(String name) {
            this.name = name;
        }

        private void dispose() {
            disposals.incrementAndGet();
        }

        private int disposals() {
            return disposals.get();
        }
    }
}
