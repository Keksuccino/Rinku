package de.keksuccino.rinku;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReadyResourceQueueTest {

    @Test
    void emptyPoolHasNoReadyResource() {
        Deque<Resource> resources = new ArrayDeque<>();

        assertNull(ReadyResourceQueue.pollFirstReady(resources, Resource::isReady));
        assertEquals(0, resources.size());
    }

    @Test
    void allPendingResourcesRemainQueuedExactlyOnce() {
        Resource first = new Resource("first", false);
        Resource second = new Resource("second", false);
        Deque<Resource> resources = new ArrayDeque<>(List.of(first, second));

        assertNull(ReadyResourceQueue.pollFirstReady(resources, Resource::isReady));
        assertIterableEquals(List.of(first, second), resources);
        assertEquals(1, resources.stream().filter(resource -> resource == first).count());
        assertEquals(1, resources.stream().filter(resource -> resource == second).count());
    }

    @Test
    void readyResourceBehindPendingResourceIsSelectedWithoutChangingRemainingOwnershipOrOrder() {
        Resource pending = new Resource("pending", false);
        Resource ready = new Resource("ready", true);
        Resource later = new Resource("later", true);
        Deque<Resource> resources = new ArrayDeque<>(List.of(pending, ready, later));

        assertSame(ready, ReadyResourceQueue.pollFirstReady(resources, Resource::isReady));
        assertIterableEquals(List.of(pending, later), resources);
        assertFalse(resources.contains(ready));
    }

    @Test
    void pendingResourceCanBeSelectedAfterTransitioningToReady() {
        Resource transitioning = new Resource("transitioning", false);
        Resource pending = new Resource("pending", false);
        Deque<Resource> resources = new ArrayDeque<>(List.of(transitioning, pending));

        assertNull(ReadyResourceQueue.pollFirstReady(resources, Resource::isReady));
        transitioning.markReady();

        assertSame(transitioning, ReadyResourceQueue.pollFirstReady(resources, Resource::isReady));
        assertIterableEquals(List.of(pending), resources);
    }

    @Test
    void queueAndReadinessPredicateAreRequired() {
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
