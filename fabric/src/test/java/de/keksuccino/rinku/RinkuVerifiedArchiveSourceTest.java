package de.keksuccino.rinku;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Covers archive identity stability across every verification pass. */
class RinkuVerifiedArchiveSourceTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void pathReplacementCannotChangeTheAlreadyOpenedArchiveIdentity() throws Exception {
        byte[] original = RinkuInstallerTestSupport.archiveBytes("opened-identity");
        byte[] replacement = RinkuInstallerTestSupport.archiveBytes("replacement-identity");
        Path archive = temporaryDirectory.resolve("candidate.tar.gz");
        Path displaced = temporaryDirectory.resolve("displaced.tar.gz");
        Files.write(archive, original);

        try (RinkuVerifiedArchiveSource source = RinkuVerifiedArchiveSource.open(archive, 1024L)) {
            Files.move(archive, displaced, StandardCopyOption.ATOMIC_MOVE);
            Files.write(archive, replacement, StandardOpenOption.CREATE_NEW);

            assertEquals(RinkuInstallerTestSupport.sha256(original), source.calculateDigest());
            source.verifiedPass(RinkuInstallerTestSupport.sha256(original), input -> assertArrayEquals(original, input.readAllBytes()));
        }
    }

    @Test
    void sameInodeRewriteAndRestoreCannotPassAbaVerification() throws Exception {
        byte[] original = new byte[64 * 1024];
        Arrays.fill(original, (byte) 'a');
        byte[] mutated = original.clone();
        Arrays.fill(mutated, 1024, mutated.length, (byte) 'b');
        Path archive = temporaryDirectory.resolve("candidate.tar.gz");
        Files.write(archive, original);

        try (RinkuVerifiedArchiveSource source = RinkuVerifiedArchiveSource.open(archive, original.length)) {
            IOException failure = assertThrows(IOException.class, () -> source.verifiedPass(RinkuInstallerTestSupport.sha256(original), input -> {
                assertEquals(1024, input.readNBytes(1024).length);
                Files.write(archive, mutated, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
                byte[] observedRemainder = input.readAllBytes();
                assertTrue(Arrays.equals(Arrays.copyOfRange(mutated, 1024, mutated.length), observedRemainder));
                Files.write(archive, original, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            }));
            assertTrue(failure.getMessage().contains("changed during a verified read pass"));
        }
    }

    @Test
    void unreadTrailingBytesRemainPartOfEveryVerifiedPass() throws Exception {
        byte[] prefix = RinkuInstallerTestSupport.archiveBytes("parsed-prefix");
        byte[] archiveBytes = Arrays.copyOf(prefix, prefix.length + 32);
        Arrays.fill(archiveBytes, prefix.length, archiveBytes.length, (byte) '!');
        Path archive = temporaryDirectory.resolve("candidate.tar.gz");
        Files.write(archive, archiveBytes);

        try (RinkuVerifiedArchiveSource source = RinkuVerifiedArchiveSource.open(archive, 1024L)) {
            assertThrows(IOException.class, () -> source.verifiedPass(RinkuInstallerTestSupport.sha256(prefix), input -> assertArrayEquals(prefix, input.readNBytes(prefix.length))));
            source.verifiedPass(RinkuInstallerTestSupport.sha256(archiveBytes), input -> assertEquals(prefix.length, input.skip(prefix.length)));
        }
    }

    @Test
    void finalComponentSymlinkIsRejectedAtOpen() throws Exception {
        Path target = temporaryDirectory.resolve("target.tar.gz");
        Path archive = temporaryDirectory.resolve("candidate.tar.gz");
        Files.write(target, RinkuInstallerTestSupport.archiveBytes("symlink-target"));
        Files.createSymbolicLink(archive, target);

        assertThrows(IOException.class, () -> RinkuVerifiedArchiveSource.open(archive, 1024L));
    }
}
