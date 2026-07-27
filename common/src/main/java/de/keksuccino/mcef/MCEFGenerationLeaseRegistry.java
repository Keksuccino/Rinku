/*
 *     MCEF (Minecraft Chromium Embedded Framework)
 *     Copyright (C) 2023 CinemaMod Group
 *
 *     This library is free software; you can redistribute it and/or
 *     modify it under the terms of the GNU Lesser General Public
 *     License as published by the Free Software Foundation; either
 *     version 2.1 of the License, or (at your option) any later version.
 */

package de.keksuccino.mcef;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** Holds process-lifetime generation leases and safely probes stale lease tokens. */
final class MCEFGenerationLeaseRegistry {
    static final String LEASE_DIRECTORY_NAME = ".mcef-generation-leases-v1";
    static final int MAX_LEASE_TOKENS = 1_024;
    // One entry beyond the published-tree cap lets a boundary-sized generation shed a stale
    // token. Sharing this allowance across the transaction bounds every caller under one lock.
    static final int MAX_LEASE_TOKENS_TO_PRUNE_PER_TRANSACTION = MAX_LEASE_TOKENS + 1;
    // Every bounded generation may need one directory open and one exact-EOF probe in addition to
    // the transaction-wide true-entry probes. Failed or mutating retries share this finite pool.
    static final int MAX_LEASE_SCAN_PROBES_PER_TRANSACTION = MAX_LEASE_TOKENS_TO_PRUNE_PER_TRANSACTION + MCEFInstallationTransaction.MAX_GENERATIONS_TO_SCAN * 2;

    private static final UUID PROCESS_TOKEN = UUID.randomUUID();
    private static final Pattern TOKEN_NAME_PATTERN = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\\.lease");
    private static final Map<Path, LeaseHandle> ACTIVE_LEASES = new HashMap<>();
    // A POSIX close can release another channel's same-process lock. An unexpected overlapping
    // probe must therefore stay open until a later probe confirms that the overlap has ended.
    private static final Map<Path, OverlapProbe> OVERLAPPING_PROBES = new HashMap<>();

    private MCEFGenerationLeaseRegistry() {
    }

    @FunctionalInterface
    interface PruneRaceHook {
        void beforeDelete(Path token) throws IOException;
    }

    @FunctionalInterface
    interface LeaseDirectoryStreamOpener {
        DirectoryStream<Path> open(Path leaseDirectory) throws IOException;
    }

    static synchronized void initializeLeaseProtocol(Path generation) throws IOException {
        Path canonicalGeneration = canonicalGeneration(generation);
        Path leaseDirectory = canonicalGeneration.resolve(LEASE_DIRECTORY_NAME);
        if (Files.exists(leaseDirectory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("JCEF archive collided with the reserved generation lease directory");
        }
        Files.createDirectory(leaseDirectory);
        if (!recognizedLeaseDirectory(canonicalGeneration).equals(leaseDirectory)) {
            throw new IOException("JCEF generation lease directory was not created safely");
        }
        forceDirectoryBestEffort(canonicalGeneration);
    }

    static synchronized Path acquire(Path generation) throws IOException {
        Path canonicalGeneration = canonicalGeneration(generation);
        LeaseHandle existing = ACTIVE_LEASES.get(canonicalGeneration);
        if (existing != null) {
            return canonicalGeneration;
        }

        Path leaseDirectory = recognizedLeaseDirectory(canonicalGeneration);
        Path token = leaseDirectory.resolve(PROCESS_TOKEN + ".lease");
        FileChannel channel = null;
        FileLock lock = null;
        boolean tokenCreated = false;
        boolean registered = false;
        try {
            channel = FileChannel.open(token, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            tokenCreated = true;
            BasicFileAttributes attributes = Files.readAttributes(token, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile() || attributes.size() != 0L) {
                throw new IOException("JCEF generation lease token was not created as an empty regular file");
            }
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException overlap) {
                throw new IOException("JCEF generation lease token unexpectedly overlaps another JVM lock", overlap);
            }
            if (lock == null) {
                throw new IOException("Could not acquire the JCEF generation lifetime lease");
            }
            channel.force(true);
            BasicFileAttributes lockedAttributes = Files.readAttributes(token, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (channel.size() != 0L || !sameFileSnapshot(attributes, lockedAttributes)) {
                throw new IOException("JCEF generation lease token changed while acquiring its lifetime lock");
            }
            forceDirectoryBestEffort(leaseDirectory);
            ACTIVE_LEASES.put(canonicalGeneration, new LeaseHandle(token, lockedAttributes, channel, lock));
            registered = true;
            return canonicalGeneration;
        } finally {
            if (!registered) {
                closeProbeBestEffort(lock, channel);
                if (tokenCreated) {
                    Files.deleteIfExists(token);
                }
            }
        }
    }

    static synchronized boolean hasRecognizedLeaseProtocol(Path generation) {
        try {
            recognizedLeaseDirectory(canonicalGeneration(generation));
            return true;
        } catch (IOException | RuntimeException ignored) {
            return false;
        }
    }

    static boolean isRecognizedTokenName(String name) {
        return name != null && TOKEN_NAME_PATTERN.matcher(name).matches();
    }

    /**
     * Removes tokens whose owning JVM has exited. The transaction proof is intentionally required:
     * its platform installer lock excludes every legitimate token creation between probing,
     * published-tree validation, and acquisition of the caller's process-lifetime lease.
     *
     * @return {@code true} if at least one valid live token remains
     */
    static synchronized boolean pruneStaleTokens(Path generation, MCEFInstallationTransaction transaction) throws IOException {
        return pruneStaleTokens(generation, transaction, null, Files::newDirectoryStream);
    }

    static synchronized boolean pruneStaleTokensForTests(Path generation, MCEFInstallationTransaction transaction, PruneRaceHook raceHook) throws IOException {
        return pruneStaleTokens(generation, transaction, Objects.requireNonNull(raceHook, "MCEF lease prune race hook must not be null"), Files::newDirectoryStream);
    }

    static synchronized boolean pruneStaleTokensForTests(Path generation, MCEFInstallationTransaction transaction, PruneRaceHook raceHook, LeaseDirectoryStreamOpener directoryStreamOpener) throws IOException {
        return pruneStaleTokens(generation, transaction, Objects.requireNonNull(raceHook, "MCEF lease prune race hook must not be null"), Objects.requireNonNull(directoryStreamOpener, "MCEF lease directory stream opener must not be null"));
    }

    private static boolean pruneStaleTokens(Path generation, MCEFInstallationTransaction transaction, PruneRaceHook raceHook, LeaseDirectoryStreamOpener directoryStreamOpener) throws IOException {
        MCEFInstallationTransaction.LeasePruningBudget pruningBudget = Objects.requireNonNull(transaction, "MCEF installation transaction must not be null").requireLeasePruningLock(generation);
        Path canonicalGeneration = canonicalGeneration(generation);
        MCEFInstallationTransaction.LeasePruningResult cachedResult = pruningBudget.successfulPrune(canonicalGeneration);
        if (cachedResult != null && reuseSuccessfulPrune(canonicalGeneration, cachedResult, pruningBudget)) {
            return cachedResult.liveToken();
        }
        Path leaseDirectory = recognizedLeaseDirectory(canonicalGeneration);
        BasicFileAttributes directoryBefore = Files.readAttributes(leaseDirectory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        LeaseHandle activeLease = ACTIVE_LEASES.get(canonicalGeneration);
        boolean deleted = false;
        boolean liveToken = false;
        try {
            // Opening and probing can both enter an attacker-controlled provider. Reserve each unit
            // before it occurs so failures and retries cannot multiply unaccounted enumeration work.
            pruningBudget.recordScanProbe();
            try (DirectoryStream<Path> entries = directoryStreamOpener.open(leaseDirectory)) {
                Iterator<Path> iterator = entries.iterator();
                while (true) {
                    pruningBudget.recordScanProbe();
                    if (!iterator.hasNext()) {
                        break;
                    }
                    // Claim the entry before next() as well as its validation and lock probe. The
                    // cap-plus-one true probe therefore fails here without fetching that extra path.
                    pruningBudget.recordEntry();
                    Path token = iterator.next();
                    boolean tokenDeleted = pruneStaleToken(canonicalGeneration, leaseDirectory, directoryBefore, token, activeLease, raceHook);
                    deleted |= tokenDeleted;
                    liveToken |= !tokenDeleted;
                }
            }
            Path recognizedAfter = recognizedLeaseDirectory(canonicalGeneration);
            BasicFileAttributes directoryAfter = Files.readAttributes(recognizedAfter, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!recognizedAfter.equals(leaseDirectory) || !sameDirectoryIdentity(directoryBefore, directoryAfter)) {
                throw new IOException("JCEF generation lease directory changed while pruning stale tokens");
            }
            // Cache only a fully successful walk. Directory identity plus last-modified time is the
            // constant-work reuse proof; a live token becoming stale without changing the directory
            // is deliberately retained until the next installer transaction.
            pruningBudget.recordSuccessfulPrune(canonicalGeneration, new MCEFInstallationTransaction.LeasePruningResult(recognizedAfter, directoryAfter, liveToken));
            return liveToken;
        } finally {
            if (deleted) {
                forceDirectoryBestEffort(leaseDirectory);
            }
        }
    }

    private static boolean reuseSuccessfulPrune(Path canonicalGeneration, MCEFInstallationTransaction.LeasePruningResult cachedResult, MCEFInstallationTransaction.LeasePruningBudget pruningBudget) throws IOException {
        boolean reusable = false;
        try {
            Path leaseDirectory = recognizedLeaseDirectory(canonicalGeneration);
            BasicFileAttributes directorySnapshot = Files.readAttributes(leaseDirectory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            reusable = leaseDirectory.equals(cachedResult.leaseDirectory()) && sameCachedDirectorySnapshot(cachedResult.directorySnapshot(), directorySnapshot);
            return reusable;
        } finally {
            // A structural or identity change must force a fresh, budgeted walk. Removing the old
            // result before that walk also prevents a failed scan or retry from reviving it.
            if (!reusable) {
                pruningBudget.invalidateSuccessfulPrune(canonicalGeneration);
            }
        }
    }

    private static boolean pruneStaleToken(Path canonicalGeneration, Path leaseDirectory, BasicFileAttributes directorySnapshot, Path token, LeaseHandle activeLease, PruneRaceHook raceHook) throws IOException {
        if (token.getParent() == null || !token.getParent().equals(leaseDirectory) || !isRecognizedTokenName(token.getFileName().toString())) {
            throw new IOException("JCEF generation contains an unrecognized lease token: " + token);
        }
        BasicFileAttributes before = readRecognizedTokenAttributes(token);
        if (activeLease != null && activeLease.token().equals(token)) {
            // On POSIX, closing any channel for a file can release every process-associated lock on
            // that file. Validate registry-owned tokens through their original lifetime channel.
            requireStableActiveLeaseToken(token, before, activeLease);
            requireStableLeaseDirectory(canonicalGeneration, leaseDirectory, directorySnapshot);
            return false;
        }

        OverlapProbe overlapProbe = OVERLAPPING_PROBES.get(token);
        FileChannel channel = overlapProbe == null ? null : overlapProbe.channel();
        boolean retainedOverlapProbe = overlapProbe != null;
        boolean keepChannelOpen = retainedOverlapProbe;
        if (retainedOverlapProbe && (!channel.isOpen() || !sameFileSnapshot(overlapProbe.snapshot(), before))) {
            throw new IOException("Overlapping JCEF generation lease token changed while retained: " + token);
        }
        if (!retainedOverlapProbe) {
            channel = FileChannel.open(token, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        }
        try {
            FileLock lock;
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException overlap) {
                if (!retainedOverlapProbe) {
                    OVERLAPPING_PROBES.put(token, new OverlapProbe(channel, before));
                    keepChannelOpen = true;
                }
                requireStableTokenSnapshot(token, before, channel);
                requireStableLeaseDirectory(canonicalGeneration, leaseDirectory, directorySnapshot);
                return false;
            }
            if (retainedOverlapProbe) {
                OVERLAPPING_PROBES.remove(token, overlapProbe);
                keepChannelOpen = false;
            }
            if (lock == null) {
                requireStableTokenSnapshot(token, before, channel);
                requireStableLeaseDirectory(canonicalGeneration, leaseDirectory, directorySnapshot);
                return false;
            }
            try (lock) {
                BasicFileAttributes locked = requireStableTokenSnapshot(token, before, channel);
                if (raceHook != null) {
                    raceHook.beforeDelete(token);
                }
                requireStableLeaseDirectory(canonicalGeneration, leaseDirectory, directorySnapshot);
                requireStableTokenSnapshot(token, locked, channel);
                // Keep the probe lock through deletion. POSIX permits unlinking an open file, and
                // Windows providers with delete-sharing permit the equivalent operation. A provider
                // that rejects it fails closed; releasing first would reopen an acquisition race.
                Files.delete(token);
                requireStableLeaseDirectory(canonicalGeneration, leaseDirectory, directorySnapshot);
                return true;
            }
        } finally {
            if (!keepChannelOpen) {
                channel.close();
            }
        }
    }

    private static BasicFileAttributes readRecognizedTokenAttributes(Path token) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(token, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.isSymbolicLink() || attributes.size() != 0L) {
            throw new IOException("JCEF generation contains a malformed lease token: " + token);
        }
        return attributes;
    }

    private static BasicFileAttributes requireStableTokenSnapshot(Path token, BasicFileAttributes expected, FileChannel channel) throws IOException {
        if (channel.size() != 0L) {
            throw new IOException("JCEF generation lease token changed while pruning: " + token);
        }
        BasicFileAttributes observed = readRecognizedTokenAttributes(token);
        if (!sameFileSnapshot(expected, observed)) {
            throw new IOException("JCEF generation lease token changed while pruning: " + token);
        }
        return observed;
    }

    private static void requireStableActiveLeaseToken(Path token, BasicFileAttributes before, LeaseHandle activeLease) throws IOException {
        if (!activeLease.channel().isOpen() || !activeLease.lock().isValid() || activeLease.channel().size() != 0L || !sameFileSnapshot(activeLease.snapshot(), before)) {
            throw new IOException("Active JCEF generation lease token changed unexpectedly: " + token);
        }
        BasicFileAttributes after = readRecognizedTokenAttributes(token);
        if (!sameFileSnapshot(before, after)) {
            throw new IOException("Active JCEF generation lease token changed while pruning: " + token);
        }
    }

    private static void requireStableLeaseDirectory(Path canonicalGeneration, Path leaseDirectory, BasicFileAttributes expected) throws IOException {
        Path observedDirectory = recognizedLeaseDirectory(canonicalGeneration);
        BasicFileAttributes observed = Files.readAttributes(observedDirectory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!observedDirectory.equals(leaseDirectory) || !sameDirectoryIdentity(expected, observed)) {
            throw new IOException("JCEF generation lease directory changed while pruning stale tokens");
        }
    }

    /**
     * Reuses the transaction's fully successful prune proof without reopening the lease directory.
     * Pruning closes every transient token probe before caching its result, which is important on
     * Windows because quarantine must move the generation only after all token handles are closed.
     * The caller keeps the installer lock through quarantine, so no legitimate lease can be created
     * after this snapshot check and before the move.
     */
    static synchronized boolean canReclaim(Path generation, MCEFInstallationTransaction transaction) throws IOException {
        MCEFInstallationTransaction.LeasePruningBudget pruningBudget = Objects.requireNonNull(transaction, "MCEF installation transaction must not be null").requireLeasePruningLock(generation);
        Path canonicalGeneration = canonicalGeneration(generation);
        if (ACTIVE_LEASES.containsKey(canonicalGeneration)) {
            return false;
        }

        MCEFInstallationTransaction.LeasePruningResult cachedResult = pruningBudget.successfulPrune(canonicalGeneration);
        if (cachedResult == null || cachedResult.liveToken()) {
            return false;
        }
        return reuseSuccessfulPrune(canonicalGeneration, cachedResult, pruningBudget);
    }

    static synchronized boolean isLeasedForTests(Path generation) {
        try {
            return ACTIVE_LEASES.containsKey(canonicalGeneration(generation));
        } catch (IOException | RuntimeException ignored) {
            return false;
        }
    }

    static synchronized void releaseForTests(Path generation, boolean deleteToken) throws IOException {
        Path canonicalGeneration = canonicalGeneration(generation);
        LeaseHandle handle = ACTIVE_LEASES.remove(canonicalGeneration);
        if (handle != null) {
            handle.close(deleteToken);
        }
    }

    static synchronized void releaseAllForTests() throws IOException {
        IOException failure = null;
        List<LeaseHandle> handles = List.copyOf(ACTIVE_LEASES.values());
        ACTIVE_LEASES.clear();
        for (LeaseHandle handle : handles) {
            try {
                handle.close(true);
            } catch (IOException closeFailure) {
                failure = appendFailure(failure, closeFailure);
            }
        }
        List<OverlapProbe> overlapProbes = List.copyOf(OVERLAPPING_PROBES.values());
        OVERLAPPING_PROBES.clear();
        for (OverlapProbe overlapProbe : overlapProbes) {
            try {
                overlapProbe.channel().close();
            } catch (IOException closeFailure) {
                failure = appendFailure(failure, closeFailure);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private static Path canonicalGeneration(Path generation) throws IOException {
        Path normalized = generation.toAbsolutePath().normalize();
        BasicFileAttributes attributes = Files.readAttributes(normalized, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory() || attributes.isSymbolicLink()) {
            throw new IOException("Missing or unsafe JCEF generation directory: " + normalized);
        }
        Path real = normalized.toRealPath();
        if (!real.equals(normalized)) {
            throw new IOException("JCEF generation directory traversed a symbolic link: " + normalized);
        }
        return real;
    }

    private static Path recognizedLeaseDirectory(Path canonicalGeneration) throws IOException {
        Path leaseDirectory = canonicalGeneration.resolve(LEASE_DIRECTORY_NAME);
        BasicFileAttributes attributes = Files.readAttributes(leaseDirectory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory() || attributes.isSymbolicLink()) {
            throw new IOException("Missing or unrecognized JCEF generation lease protocol");
        }
        Path real = leaseDirectory.toRealPath();
        if (!real.equals(leaseDirectory) || !real.getParent().equals(canonicalGeneration)) {
            throw new IOException("Unsafe JCEF generation lease directory");
        }
        return real;
    }

    private static boolean sameFileSnapshot(BasicFileAttributes before, BasicFileAttributes after) {
        return after.isRegularFile() && before.size() == after.size() && before.creationTime().equals(after.creationTime()) && before.lastModifiedTime().equals(after.lastModifiedTime()) && sameFileKey(before.fileKey(), after.fileKey());
    }

    private static boolean sameDirectorySnapshot(BasicFileAttributes before, BasicFileAttributes after) {
        return after.isDirectory() && before.creationTime().equals(after.creationTime()) && before.lastModifiedTime().equals(after.lastModifiedTime()) && sameFileKey(before.fileKey(), after.fileKey());
    }

    private static boolean sameCachedDirectorySnapshot(BasicFileAttributes before, BasicFileAttributes after) {
        // A provider without stable file keys cannot prove that the directory was not replaced, so
        // it receives the bounded full scan instead of cache reuse. Size is provider-specific for a
        // directory, but comparing a value from the same provider gives another conservative guard.
        return before.fileKey() != null && after.fileKey() != null && before.size() == after.size() && sameDirectorySnapshot(before, after);
    }

    private static boolean sameDirectoryIdentity(BasicFileAttributes before, BasicFileAttributes after) {
        return after.isDirectory() && !after.isSymbolicLink() && before.creationTime().equals(after.creationTime()) && sameFileKey(before.fileKey(), after.fileKey());
    }

    private static boolean sameFileKey(Object before, Object after) {
        return Objects.equals(before, after);
    }

    private static void closeProbeBestEffort(FileLock lock, FileChannel channel) {
        try {
            if (lock != null && lock.isValid()) {
                lock.release();
            }
        } catch (IOException ignored) {
        }
        try {
            if (channel != null) {
                channel.close();
            }
        } catch (IOException ignored) {
        }
    }

    private static void forceDirectoryBestEffort(Path directory) {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException | UnsupportedOperationException ignored) {
        }
    }

    private static IOException appendFailure(IOException existing, IOException additional) {
        if (existing == null) {
            return additional;
        }
        existing.addSuppressed(additional);
        return existing;
    }

    private record OverlapProbe(FileChannel channel, BasicFileAttributes snapshot) {
    }

    private record LeaseHandle(Path token, BasicFileAttributes snapshot, FileChannel channel, FileLock lock) {
        private void close(boolean deleteToken) throws IOException {
            IOException failure = null;
            try {
                if (lock.isValid()) {
                    lock.release();
                }
            } catch (IOException releaseFailure) {
                failure = releaseFailure;
            }
            try {
                channel.close();
            } catch (IOException closeFailure) {
                failure = appendFailure(failure, closeFailure);
            }
            if (deleteToken) {
                try {
                    Files.deleteIfExists(token);
                } catch (IOException deleteFailure) {
                    failure = appendFailure(failure, deleteFailure);
                }
            }
            if (failure != null) {
                throw failure;
            }
        }
    }
}
