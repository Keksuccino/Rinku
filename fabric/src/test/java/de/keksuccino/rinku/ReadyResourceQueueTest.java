package de.keksuccino.rinku;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReadyResourceQueueTest {

    @Test
    void returnsNullFromAnEmptyQueue() {
        Deque<Resource> resources = new ArrayDeque<>();

        assertNull(ReadyResourceQueue.pollFirstReady(resources, Resource::ready));
        assertEquals(0, resources.size());
    }

    @Test
    void leavesPendingResourcesQueuedWhenNoneAreReady() {
        Deque<Resource> resources = new ArrayDeque<>();
        resources.addLast(new Resource("first", false));
        resources.addLast(new Resource("second", false));

        assertNull(ReadyResourceQueue.pollFirstReady(resources, Resource::ready));
        assertEquals(2, resources.size());
        assertEquals("first", resources.removeFirst().name());
        assertEquals("second", resources.removeFirst().name());
    }

    @Test
    void removesOnlyTheFirstReadyResource() {
        Resource pending = new Resource("pending", false);
        Resource ready = new Resource("ready", true);
        Resource later = new Resource("later", true);
        Deque<Resource> resources = new ArrayDeque<>();
        resources.addLast(pending);
        resources.addLast(ready);
        resources.addLast(later);

        assertEquals(ready, ReadyResourceQueue.pollFirstReady(resources, Resource::ready));
        assertEquals(2, resources.size());
        assertEquals(pending, resources.removeFirst());
        assertEquals(later, resources.removeFirst());
    }

    @Test
    void selectsAPendingResourceOnlyAfterItBecomesReady() {
        MutableResource resource = new MutableResource();
        Deque<MutableResource> resources = new ArrayDeque<>();
        resources.addLast(resource);

        assertNull(ReadyResourceQueue.pollFirstReady(resources, MutableResource::isReady));
        assertEquals(1, resources.size());
        resource.ready = true;
        assertEquals(resource, ReadyResourceQueue.pollFirstReady(resources, MutableResource::isReady));
        assertEquals(0, resources.size());
    }

    @Test
    void rejectsMissingQueueOrReadinessPredicate() {
        Deque<Resource> resources = new ArrayDeque<>();

        assertThrows(NullPointerException.class, () -> ReadyResourceQueue.pollFirstReady(null, Resource::ready));
        assertThrows(NullPointerException.class, () -> ReadyResourceQueue.pollFirstReady(resources, null));
    }

    private record Resource(String name, boolean ready) {}

    private static final class MutableResource {
        private boolean ready;

        private boolean isReady() {
            return ready;
        }
    }
}
