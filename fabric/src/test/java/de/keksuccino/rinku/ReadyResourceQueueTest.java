package de.keksuccino.rinku;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReadyResourceQueueTest {

    @Test
    void returnsNullForEmptyQueue() {
        Deque<Resource> resources = new ArrayDeque<>();

        assertNull(ReadyResourceQueue.pollFirstReady(resources, Resource::isReady));
        assertEquals(0, resources.size());
    }

    @Test
    void leavesAllPendingResourcesInOriginalOrder() {
        Resource first = new Resource("first", false);
        Resource second = new Resource("second", false);
        Deque<Resource> resources = new ArrayDeque<>();
        resources.addLast(first);
        resources.addLast(second);

        assertNull(ReadyResourceQueue.pollFirstReady(resources, Resource::isReady));
        assertEquals(2, resources.size());
        assertSame(first, resources.removeFirst());
        assertSame(second, resources.removeFirst());
    }

    @Test
    void removesReadyResourceBehindPendingWithoutReorderingRemainingResources() {
        Resource pending = new Resource("pending", false);
        Resource ready = new Resource("ready", true);
        Resource later = new Resource("later", true);
        Deque<Resource> resources = new ArrayDeque<>();
        resources.addLast(pending);
        resources.addLast(ready);
        resources.addLast(later);

        assertSame(ready, ReadyResourceQueue.pollFirstReady(resources, Resource::isReady));
        assertEquals(2, resources.size());
        assertSame(pending, resources.removeFirst());
        assertSame(later, resources.removeFirst());
    }

    @Test
    void selectsPendingResourceAfterItTransitionsToReady() {
        Resource resource = new Resource("warming", false);
        Deque<Resource> resources = new ArrayDeque<>();
        resources.addLast(resource);

        assertNull(ReadyResourceQueue.pollFirstReady(resources, Resource::isReady));
        assertSame(resource, resources.getFirst());

        resource.markReady();

        assertSame(resource, ReadyResourceQueue.pollFirstReady(resources, Resource::isReady));
        assertEquals(0, resources.size());
    }

    @Test
    void rejectsMissingQueueOrReadinessPredicate() {
        Deque<Resource> resources = new ArrayDeque<>();

        assertThrows(NullPointerException.class, () -> ReadyResourceQueue.pollFirstReady(null, Resource::isReady));
        assertThrows(NullPointerException.class, () -> ReadyResourceQueue.pollFirstReady(resources, null));
    }

    private static final class Resource {

        private final String name;
        private boolean ready;

        private Resource(String name, boolean ready) {
            this.name = name;
            this.ready = ready;
        }

        private boolean isReady() {
            return ready;
        }

        private void markReady() {
            ready = true;
        }

        @Override
        public String toString() {
            return name;
        }
    }
}
