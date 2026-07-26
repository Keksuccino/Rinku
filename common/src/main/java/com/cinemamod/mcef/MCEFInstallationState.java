/*
 *     MCEF (Minecraft Chromium Embedded Framework)
 *     Copyright (C) 2023 CinemaMod Group
 *
 *     This library is free software; you can redistribute it and/or
 *     modify it under the terms of the GNU Lesser General Public
 *     License as published by the Free Software Foundation; either
 *     version 2.1 of the License, or (at your option) any later version.
 */

package com.cinemamod.mcef;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Strict, versioned metadata shared by transactions, generations, and the active selector. */
final class MCEFInstallationState {
    enum StateKind {
        TRANSACTION("transaction"),
        GENERATION("generation"),
        SELECTION("selection");

        private final String serializedName;

        StateKind(String serializedName) {
            this.serializedName = serializedName;
        }
    }

    enum TransactionPhase {
        PREPARED("prepared"),
        PROMOTING("promoting"),
        COMMITTED("committed");

        private final String serializedName;

        TransactionPhase(String serializedName) {
            this.serializedName = serializedName;
        }
    }

    enum ChecksumVerification {
        PENDING("pending"),
        CHECKSUM_VERIFIED("checksum-verified"),
        UNCHECKED("unchecked");

        private final String serializedName;

        ChecksumVerification(String serializedName) {
            this.serializedName = serializedName;
        }
    }

    static final String OFFICIAL_CHECKSUM_SOURCE = "official";
    static final String NO_CHECKSUM_SOURCE = "none";
    static final String LEGACY_UNKNOWN_CHECKSUM_SOURCE = "legacy-unknown";
    private static final String PENDING_CHECKSUM_SOURCE = "pending";
    private static final String CONFIGURED_CHECKSUM_SOURCE_PREFIX = "mirror-sha256:";
    private static final int FORMAT_VERSION = 3;
    private static final int LEGACY_FORMAT_VERSION = 2;
    private static final int MAX_STATE_BYTES = 16 * 1024;
    private static final String PENDING_VALUE = "pending";
    private static final Pattern PLATFORM_PATTERN = Pattern.compile("[a-z0-9_]+");
    private static final Pattern COMMIT_PATTERN = Pattern.compile("(?:[0-9a-f]{40}|[0-9a-f]{64})");
    private static final Pattern DIGEST_PATTERN = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern CONFIGURED_CHECKSUM_SOURCE_PATTERN = Pattern.compile("mirror-sha256:[0-9a-f]{64}");
    private static final Pattern GENERATION_PATTERN = Pattern.compile("(?:[0-9a-f]{40}|[0-9a-f]{64})-[0-9a-f]{64}-[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
    private static final Set<String> STATE_KEYS = Set.of("format-version", "kind", "transaction-id", "platform", "java-cef-commit", "archive-sha256", "manifest-sha256", "checksum-verification", "checksum-source", "generation", "phase");
    private static final Set<String> LEGACY_STATE_KEYS = Set.of("format-version", "kind", "transaction-id", "platform", "java-cef-commit", "archive-sha256", "manifest-sha256", "archive-authentication", "generation", "phase");
    private static final Set<String> ALL_STATE_KEYS = Set.of("format-version", "kind", "transaction-id", "platform", "java-cef-commit", "archive-sha256", "manifest-sha256", "checksum-verification", "checksum-source", "archive-authentication", "generation", "phase");

    private MCEFInstallationState() {
    }

    static StateRecord readQuietly(Path path, StateKind expectedKind) {
        try {
            StateRecord state = read(path);
            return state.kind() == expectedKind ? state : null;
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    static StateRecord read(Path path) throws IOException {
        String content = readSmallUtf8File(path, "JCEF installation state");
        List<String> lines = content.lines().toList();
        Map<String, String> values = new HashMap<>();
        for (String line : lines) {
            if (line.isEmpty()) {
                continue;
            }
            int separator = line.indexOf('=');
            if (separator <= 0 || separator == line.length() - 1) {
                throw new IOException("Malformed JCEF installation state: " + path);
            }
            String key = line.substring(0, separator);
            String value = line.substring(separator + 1);
            if (!ALL_STATE_KEYS.contains(key) || values.putIfAbsent(key, value) != null) {
                throw new IOException("Malformed JCEF installation state: " + path);
            }
        }

        int formatVersion;
        try {
            formatVersion = Integer.parseInt(values.get("format-version"));
        } catch (NumberFormatException | NullPointerException exception) {
            throw new IOException("Invalid JCEF installation state version", exception);
        }
        Set<String> expectedKeys;
        if (formatVersion == FORMAT_VERSION) {
            expectedKeys = STATE_KEYS;
        } else if (formatVersion == LEGACY_FORMAT_VERSION) {
            expectedKeys = LEGACY_STATE_KEYS;
        } else {
            throw new IOException("Unsupported JCEF installation state version: " + formatVersion);
        }
        if (!values.keySet().equals(expectedKeys)) {
            throw new IOException("Incomplete JCEF installation state: " + path);
        }

        StateKind kind = parseKind(values.get("kind"));
        UUID id = parseUuid(values.get("transaction-id"));
        String platform = values.get("platform");
        String commit = values.get("java-cef-commit");
        String digest = values.get("archive-sha256");
        String manifestDigest = values.get("manifest-sha256");
        ChecksumVerification checksumVerification = formatVersion == FORMAT_VERSION ? parseChecksumVerification(values.get("checksum-verification")) : parseLegacyChecksumVerification(values.get("archive-authentication"));
        String checksumSource = formatVersion == FORMAT_VERSION ? values.get("checksum-source") : legacyChecksumSource(checksumVerification);
        String generation = values.get("generation");
        TransactionPhase phase = parsePhase(values.get("phase"));
        if (id == null || !isPlatform(platform) || !isCommit(commit)) {
            throw new IOException("Invalid identity in JCEF installation state");
        }
        if (!PENDING_VALUE.equals(digest) && !isDigest(digest)) {
            throw new IOException("Invalid archive digest in JCEF installation state");
        }
        if (!PENDING_VALUE.equals(manifestDigest) && !isDigest(manifestDigest)) {
            throw new IOException("Invalid manifest digest in JCEF installation state");
        }
        if (!PENDING_VALUE.equals(generation) && !isGeneration(generation)) {
            throw new IOException("Invalid generation in JCEF installation state");
        }
        if (!isValidChecksumSource(checksumVerification, checksumSource)) {
            throw new IOException("Invalid JCEF checksum verification source");
        }
        if (phase == TransactionPhase.PREPARED && (!PENDING_VALUE.equals(digest) || !PENDING_VALUE.equals(manifestDigest) || checksumVerification != ChecksumVerification.PENDING || !PENDING_VALUE.equals(generation) || kind != StateKind.TRANSACTION)) {
            throw new IOException("Invalid prepared JCEF installation state");
        }
        if (phase != TransactionPhase.PREPARED && (PENDING_VALUE.equals(digest) || PENDING_VALUE.equals(manifestDigest) || checksumVerification == ChecksumVerification.PENDING || PENDING_VALUE.equals(generation))) {
            throw new IOException("Incomplete promoted JCEF installation state");
        }
        if (kind != StateKind.TRANSACTION && phase != TransactionPhase.COMMITTED) {
            throw new IOException("Invalid published JCEF installation state");
        }
        StateRecord state = new StateRecord(kind, id, platform, commit, digest, manifestDigest, checksumVerification, checksumSource, generation, phase);
        if (phase != TransactionPhase.PREPARED && !state.generationIsExact()) {
            throw new IOException("JCEF generation identity does not match its transaction metadata");
        }
        return state;
    }

    static boolean isPlatform(String platform) {
        return platform != null && PLATFORM_PATTERN.matcher(platform).matches();
    }

    static boolean isDigest(String digest) {
        return digest != null && DIGEST_PATTERN.matcher(digest).matches();
    }

    static boolean isGeneration(String generation) {
        return generation != null && GENERATION_PATTERN.matcher(generation).matches();
    }

    static String normalizeCommit(String commit) {
        if (commit == null) {
            throw new IllegalArgumentException("java-cef commit hash is missing");
        }
        String normalized = commit.toLowerCase(Locale.ROOT);
        if (!isCommit(normalized)) {
            throw new IllegalArgumentException("Invalid java-cef commit hash: " + commit);
        }
        return normalized;
    }

    static String normalizeDigest(String digest) {
        if (digest == null) {
            throw new IllegalArgumentException("JCEF archive digest is missing");
        }
        String normalized = digest.toLowerCase(Locale.ROOT);
        if (!isDigest(normalized)) {
            throw new IllegalArgumentException("Invalid JCEF archive digest: " + digest);
        }
        return normalized;
    }

    static String configuredChecksumSource(String canonicalMirrorDigest) {
        String normalizedDigest = normalizeDigest(canonicalMirrorDigest);
        return CONFIGURED_CHECKSUM_SOURCE_PREFIX + normalizedDigest;
    }

    static String normalizeChecksumSource(String checksumSource) {
        if (checksumSource == null) {
            throw new IllegalArgumentException("JCEF checksum source is missing");
        }
        String normalized = checksumSource.toLowerCase(Locale.ROOT);
        if (!OFFICIAL_CHECKSUM_SOURCE.equals(normalized) && !CONFIGURED_CHECKSUM_SOURCE_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException("Invalid JCEF checksum source");
        }
        return normalized;
    }

    static boolean isCommit(String commit) {
        return commit != null && COMMIT_PATTERN.matcher(commit).matches();
    }

    private static StateKind parseKind(String value) throws IOException {
        for (StateKind kind : StateKind.values()) {
            if (kind.serializedName.equals(value)) {
                return kind;
            }
        }
        throw new IOException("Invalid JCEF installation state kind: " + value);
    }

    private static TransactionPhase parsePhase(String value) throws IOException {
        for (TransactionPhase phase : TransactionPhase.values()) {
            if (phase.serializedName.equals(value)) {
                return phase;
            }
        }
        throw new IOException("Invalid JCEF installation transaction phase: " + value);
    }

    private static ChecksumVerification parseChecksumVerification(String value) throws IOException {
        for (ChecksumVerification verification : ChecksumVerification.values()) {
            if (verification.serializedName.equals(value)) {
                return verification;
            }
        }
        throw new IOException("Invalid JCEF checksum verification state: " + value);
    }

    private static ChecksumVerification parseLegacyChecksumVerification(String value) throws IOException {
        if (value == null) {
            throw new IOException("Missing legacy JCEF checksum verification state");
        }
        return switch (value) {
            case "pending" -> ChecksumVerification.PENDING;
            case "authenticated" -> ChecksumVerification.CHECKSUM_VERIFIED;
            case "unchecked" -> ChecksumVerification.UNCHECKED;
            default -> throw new IOException("Invalid legacy JCEF checksum verification state: " + value);
        };
    }

    private static String legacyChecksumSource(ChecksumVerification verification) {
        return switch (verification) {
            case PENDING -> PENDING_CHECKSUM_SOURCE;
            case CHECKSUM_VERIFIED -> LEGACY_UNKNOWN_CHECKSUM_SOURCE;
            case UNCHECKED -> NO_CHECKSUM_SOURCE;
        };
    }

    private static boolean isValidChecksumSource(ChecksumVerification verification, String checksumSource) {
        if (checksumSource == null) {
            return false;
        }
        return switch (verification) {
            case PENDING -> PENDING_CHECKSUM_SOURCE.equals(checksumSource);
            case UNCHECKED -> NO_CHECKSUM_SOURCE.equals(checksumSource);
            case CHECKSUM_VERIFIED -> OFFICIAL_CHECKSUM_SOURCE.equals(checksumSource) || LEGACY_UNKNOWN_CHECKSUM_SOURCE.equals(checksumSource) || CONFIGURED_CHECKSUM_SOURCE_PATTERN.matcher(checksumSource).matches();
        };
    }

    private static String readSmallUtf8File(Path path, String description) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Missing or unsafe " + description + ": " + path);
        }
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            long size = channel.size();
            if (size <= 0L || size > MAX_STATE_BYTES) {
                throw new IOException(description + " has an invalid size: " + path);
            }
            ByteBuffer buffer = ByteBuffer.allocate(Math.toIntExact(size));
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) < 0) {
                    throw new IOException(description + " changed while it was being read: " + path);
                }
            }
            if (channel.size() != size) {
                throw new IOException(description + " changed while it was being read: " + path);
            }
            return StandardCharsets.UTF_8.decode(buffer.flip()).toString();
        }
    }

    private static UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    record StateRecord(StateKind kind, UUID transactionId, String platform, String javaCefCommit, String archiveDigest, String manifestDigest, ChecksumVerification checksumVerification, String checksumSource, String generation, TransactionPhase phase) {
        static StateRecord prepared(UUID transactionId, String platform, String javaCefCommit) {
            return new StateRecord(StateKind.TRANSACTION, transactionId, platform, javaCefCommit, PENDING_VALUE, PENDING_VALUE, ChecksumVerification.PENDING, PENDING_CHECKSUM_SOURCE, PENDING_VALUE, TransactionPhase.PREPARED);
        }

        static StateRecord promoting(UUID transactionId, String platform, String javaCefCommit, String archiveDigest, String manifestDigest, ChecksumVerification checksumVerification, String checksumSource, String generation) {
            return new StateRecord(StateKind.TRANSACTION, transactionId, platform, javaCefCommit, archiveDigest, manifestDigest, checksumVerification, checksumSource, generation, TransactionPhase.PROMOTING);
        }

        static StateRecord committed(StateKind kind, UUID transactionId, String platform, String javaCefCommit, String archiveDigest, String manifestDigest, ChecksumVerification checksumVerification, String checksumSource, String generation) {
            return new StateRecord(kind, transactionId, platform, javaCefCommit, archiveDigest, manifestDigest, checksumVerification, checksumSource, generation, TransactionPhase.COMMITTED);
        }

        boolean matches(String expectedCommit, String expectedDigest) {
            return javaCefCommit.equals(expectedCommit) && (expectedDigest == null || archiveDigest.equals(expectedDigest));
        }

        boolean sameIdentity(StateRecord other) {
            return other != null && transactionId.equals(other.transactionId) && platform.equals(other.platform) && javaCefCommit.equals(other.javaCefCommit) && archiveDigest.equals(other.archiveDigest) && manifestDigest.equals(other.manifestDigest) && checksumVerification == other.checksumVerification && checksumSource.equals(other.checksumSource) && generation.equals(other.generation);
        }

        boolean sameGenerationIdentityIgnoringChecksumVerification(StateRecord other) {
            return other != null && transactionId.equals(other.transactionId) && platform.equals(other.platform) && javaCefCommit.equals(other.javaCefCommit) && archiveDigest.equals(other.archiveDigest) && manifestDigest.equals(other.manifestDigest) && generation.equals(other.generation);
        }

        boolean hasExactDigest() {
            return isDigest(archiveDigest);
        }

        boolean generationIsExact() {
            return hasExactDigest() && isDigest(manifestDigest) && checksumVerification != ChecksumVerification.PENDING && isGeneration(generation) && generation.equals(javaCefCommit + "-" + archiveDigest + "-" + transactionId);
        }

        boolean checksumVerified() {
            return checksumVerification == ChecksumVerification.CHECKSUM_VERIFIED;
        }

        boolean checksumVerifiedBy(Set<String> allowedChecksumSources) {
            return checksumVerified() && allowedChecksumSources != null && allowedChecksumSources.contains(checksumSource);
        }

        String serialize() {
            return "format-version=" + FORMAT_VERSION + "\n" +
                    "kind=" + kind.serializedName + "\n" +
                    "transaction-id=" + transactionId + "\n" +
                    "platform=" + platform + "\n" +
                    "java-cef-commit=" + javaCefCommit + "\n" +
                    "archive-sha256=" + archiveDigest + "\n" +
                    "manifest-sha256=" + manifestDigest + "\n" +
                    "checksum-verification=" + checksumVerification.serializedName + "\n" +
                    "checksum-source=" + checksumSource + "\n" +
                    "generation=" + generation + "\n" +
                    "phase=" + phase.serializedName + "\n";
        }
    }
}
