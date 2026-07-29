package de.keksuccino.rinku;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReadyResourceQueueTest {

    @Test
    void returnsNullForEmptyQueue() {
        Deque<Resource> resources = new ArrayDeque<>();

        assertNull(ReadyResourceQueue.pollFirstReady(resources, Resource::ready));
        assertEquals(0, resources.size());
    }

    @Test
    void retainsEveryPendingResourceWithoutDuplication() {
        Resource first = new Resource("first", false);
        Resource second = new Resource("second", false);
        Deque<Resource> resources = new ArrayDeque<>(List.of(first, second));

        assertNull(ReadyResourceQueue.pollFirstReady(resources, Resource::ready));
        assertEquals(2, resources.size());
        assertIterableEquals(List.of(first, second), resources);
    }

    @Test
    void removesOnlyFirstReadyResourceBehindPendingEntry() {
        Resource pending = new Resource("pending", false);
        Resource ready = new Resource("ready", true);
        Resource laterReady = new Resource("later-ready", true);
        Deque<Resource> resources = new ArrayDeque<>(List.of(pending, ready, laterReady));

        assertSame(ready, ReadyResourceQueue.pollFirstReady(resources, Resource::ready));
        assertEquals(2, resources.size());
        assertIterableEquals(List.of(pending, laterReady), resources);
    }

    @Test
    void acquiresRetainedResourceAfterItBecomesReady() {
        Resource resource = new Resource("initially-pending", false);
        Deque<Resource> resources = new ArrayDeque<>(List.of(resource));

        assertNull(ReadyResourceQueue.pollFirstReady(resources, Resource::ready));
        resource.markReady();

        assertSame(resource, ReadyResourceQueue.pollFirstReady(resources, Resource::ready));
        assertEquals(0, resources.size());
    }

    @Test
    void rejectsMissingQueueOrReadinessPredicate() {
        Deque<Resource> resources = new ArrayDeque<>();

        assertThrows(NullPointerException.class, () -> ReadyResourceQueue.pollFirstReady(null, Resource::ready));
        assertThrows(NullPointerException.class, () -> ReadyResourceQueue.pollFirstReady(resources, null));
    }

    private static final class Resource {

        private final String name;
        private boolean ready;

        private Resource(String name, boolean ready) {
            this.name = name;
            this.ready = ready;
        }

        private boolean ready() {
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
