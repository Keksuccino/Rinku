package de.keksuccino.rinku;

final class TestThreads {

    private TestThreads() {
    }

    static Thread start(Runnable task) {
        Thread thread = new Thread(task);
        thread.start();
        return thread;
    }
}
