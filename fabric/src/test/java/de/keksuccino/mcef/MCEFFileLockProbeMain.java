package de.keksuccino.mcef;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Standalone child-JVM probe for the installer's operating-system file lock. */
public final class MCEFFileLockProbeMain {
    private MCEFFileLockProbeMain() {
    }

    public static void main(String[] arguments) throws Exception {
        try (FileChannel channel = FileChannel.open(Path.of(arguments[0]), StandardOpenOption.WRITE); FileLock lock = channel.tryLock()) {
            System.out.println(lock == null ? "LOCKED" : "UNLOCKED");
        }
    }
}
