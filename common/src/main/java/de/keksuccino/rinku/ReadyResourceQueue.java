package de.keksuccino.rinku;

import javax.annotation.Nullable;

import java.util.Deque;
import java.util.Iterator;
import java.util.Objects;
import java.util.function.Predicate;

/** Selects usable pooled resources without discarding entries whose asynchronous setup is still pending. */
final class ReadyResourceQueue {

    private ReadyResourceQueue() {}

    /**
     * The caller must hold the queue's lifecycle lock while invoking this method. Pending entries stay in place so
     * they can satisfy a later acquisition after their independent initialization completes.
     */
    @Nullable
    static <T> T pollFirstReady(Deque<T> resources, Predicate<? super T> readiness) {
        Objects.requireNonNull(resources, "resources");
        Objects.requireNonNull(readiness, "readiness");
        Iterator<T> iterator = resources.iterator();
        while (iterator.hasNext()) {
            T candidate = iterator.next();
            if (!readiness.test(candidate)) continue;
            iterator.remove();
            return candidate;
        }
        return null;
    }
}
