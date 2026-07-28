package de.keksuccino.rinku;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Keeps one no-follow archive handle open across checksum validation and extraction. Every consumer
 * pass hashes all raw archive bytes, including unread parser trailers, so path replacement and
 * same-inode mutation cannot pass with a restored pathname.
 */
public final class RinkuVerifiedArchiveSource implements AutoCloseable {
    @FunctionalInterface
    public interface InputConsumer {
        void accept(InputStream input) throws IOException;
    }

    private static final int DRAIN_BUFFER_SIZE_BYTES = 16 * 1024;
    private static final Pattern SHA256_PATTERN = Pattern.compile("[0-9a-f]{64}");

    private final Path path;
    private final FileChannel channel;
    private final long capturedSize;
    private boolean passActive;
    private boolean closed;

    private RinkuVerifiedArchiveSource(Path path, FileChannel channel, long capturedSize) {
        this.path = path;
        this.channel = channel;
        this.capturedSize = capturedSize;
    }

    public static RinkuVerifiedArchiveSource open(Path archive, long maxBytes) throws IOException {
        Path normalizedArchive = Objects.requireNonNull(archive, "JCEF archive must not be null").toAbsolutePath().normalize();
        if (maxBytes <= 0L) {
            throw new IllegalArgumentException("Maximum JCEF archive size must be positive");
        }
        if (!Files.isRegularFile(normalizedArchive, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Missing or unsafe JCEF archive: " + normalizedArchive);
        }

        FileChannel channel = FileChannel.open(normalizedArchive, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        try {
            long size = channel.size();
            if (size <= 0L || size > maxBytes) {
                throw new IOException("JCEF archive size is outside the configured limit");
            }
            return new RinkuVerifiedArchiveSource(normalizedArchive, channel, size);
        } catch (IOException | RuntimeException | Error failure) {
            try {
                channel.close();
            } catch (IOException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    long size() {
        return capturedSize;
    }

    public String calculateDigest() throws IOException {
        return completePass(null, ignored -> {});
    }

    public void verifiedPass(String expectedDigest, InputConsumer consumer) throws IOException {
        completePass(normalizeDigest(expectedDigest), consumer);
    }

    private synchronized String completePass(String expectedDigest, InputConsumer consumer) throws IOException {
        Objects.requireNonNull(consumer, "JCEF archive input consumer must not be null");
        requireOpen();
        if (passActive) {
            throw new IllegalStateException("A JCEF archive verification pass is already active");
        }
        if (channel.size() != capturedSize) {
            throw new IOException("JCEF archive size changed before verification");
        }

        passActive = true;
        try {
            channel.position(0L);
            MessageDigest digest = sha256Digest();
            CountingInputStream boundedInput = new CountingInputStream(Channels.newInputStream(channel), capturedSize);
            DigestInputStream digestInput = new DigestInputStream(boundedInput, digest);
            InputStream closeShield = new FilterInputStream(digestInput) {
                @Override
                public void close() {
                    // Parsers must be free to close their stream without closing the identity-stable
                    // channel needed by the following verification or extraction pass.
                }

                @Override
                public long skip(long byteCount) throws IOException {
                    if (byteCount <= 0L) {
                        return 0L;
                    }
                    byte[] buffer = new byte[DRAIN_BUFFER_SIZE_BYTES];
                    long skipped = 0L;
                    while (skipped < byteCount) {
                        int read = read(buffer, 0, (int) Math.min(buffer.length, byteCount - skipped));
                        if (read < 0) {
                            break;
                        }
                        skipped += read;
                    }
                    return skipped;
                }
            };
            consumer.accept(closeShield);
            drain(digestInput);
            if (boundedInput.count() != capturedSize || channel.size() != capturedSize) {
                throw new IOException("JCEF archive size changed during verification");
            }
            String actualDigest = HexFormat.of().formatHex(digest.digest()).toLowerCase(Locale.ROOT);
            if (expectedDigest != null && !expectedDigest.equals(actualDigest)) {
                throw new IOException("JCEF archive changed during a verified read pass");
            }
            return actualDigest;
        } finally {
            passActive = false;
        }
    }

    private static MessageDigest sha256Digest() throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IOException("SHA-256 is unavailable", unavailable);
        }
    }

    private static String normalizeDigest(String digest) {
        if (digest == null) {
            throw new IllegalArgumentException("JCEF archive digest is missing");
        }
        String normalized = digest.toLowerCase(Locale.ROOT);
        if (!SHA256_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException("Invalid JCEF archive digest");
        }
        return normalized;
    }

    private static void drain(InputStream input) throws IOException {
        byte[] buffer = new byte[DRAIN_BUFFER_SIZE_BYTES];
        while (input.read(buffer) != -1) {
        }
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("JCEF archive source is closed: " + path);
        }
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) {
            return;
        }
        if (passActive) {
            throw new IllegalStateException("Cannot close a JCEF archive source during a verification pass");
        }
        closed = true;
        channel.close();
    }

    private static final class CountingInputStream extends FilterInputStream {
        private final long expectedSize;
        private long count;

        private CountingInputStream(InputStream input, long expectedSize) {
            super(input);
            this.expectedSize = expectedSize;
        }

        private long count() {
            return count;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0) {
                recordRead(1L);
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = super.read(buffer, offset, length);
            if (read > 0) {
                recordRead(read);
            }
            return read;
        }

        private void recordRead(long bytes) throws IOException {
            try {
                count = Math.addExact(count, bytes);
            } catch (ArithmeticException overflow) {
                throw new IOException("JCEF archive byte count overflowed", overflow);
            }
            if (count > expectedSize) {
                throw new IOException("JCEF archive grew during verification");
            }
        }
    }
}
