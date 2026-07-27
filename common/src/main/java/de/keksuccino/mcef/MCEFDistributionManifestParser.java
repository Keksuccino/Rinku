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

import de.keksuccino.mcef.MCEFDistributionManifest.ManifestData;
import de.keksuccino.mcef.MCEFDistributionManifest.ManifestFile;
import de.keksuccino.mcef.MCEFDistributionManifest.ParsedManifest;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/** Strict, allocation-bounded parser for the schema-2 JCEF distribution manifest. */
final class MCEFDistributionManifestParser {
    private static final int MAX_MANIFEST_BYTES = 16 * 1024 * 1024;
    private static final int MAX_AUXILIARY_JARS = 128;
    private static final int MAX_JSON_STRING_CHARS = 8 * 1024;
    private static final int MAX_JSON_ELEMENTS = 4 * (MCEFDistributionManifest.MAX_DISTRIBUTION_FILES + MCEFDistributionManifest.MAX_RUNTIME_FILES) + MCEFDistributionManifest.MAX_DISTRIBUTION_DIRECTORIES + 2_048;
    private static final int READ_BUFFER_BYTES = 64 * 1024;
    private static final Set<String> TOP_LEVEL_KEYS = Set.of("manifest_schema", "archive_root", "cef_api_version", "cef_version", "java_release", "java_cef_commit", "jogl_swing_osr_supported", "jogamp_jars", "jcef_jars", "distribution_directories", "distribution_files", "runtime_entries", "runtime_files", "target");
    private static final Set<String> FILE_KEYS = Set.of("path", "size", "sha256");

    private MCEFDistributionManifestParser() {
    }

    static ParsedManifest parse(Path path) throws IOException {
        ManifestBytes manifestBytes = readManifest(path);
        ManifestData manifest = new StrictParser(decodeUtf8(manifestBytes.bytes())).parse();
        return new ParsedManifest(manifest, manifestBytes.sha256());
    }

    static String digest(Path path) throws IOException {
        return readManifest(path).sha256();
    }

    private static ManifestBytes readManifest(Path path) throws IOException {
        BasicFileAttributes before;
        try {
            before = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException failure) {
            throw new IOException("Missing JCEF distribution manifest: " + path, failure);
        }
        if (!before.isRegularFile() || before.size() <= 0L || before.size() > MAX_MANIFEST_BYTES) {
            throw new IOException("JCEF distribution manifest has an invalid size: " + path);
        }

        MessageDigest digest = newSha256();
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.toIntExact(before.size()));
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            if (channel.size() != before.size()) {
                throw new IOException("JCEF distribution manifest changed while opening: " + path);
            }
            ByteBuffer buffer = ByteBuffer.allocate(READ_BUFFER_BYTES);
            while (true) {
                int count = channel.read(buffer);
                if (count < 0) {
                    break;
                }
                if (count == 0) {
                    continue;
                }
                if (output.size() + count > MAX_MANIFEST_BYTES) {
                    throw new IOException("JCEF distribution manifest exceeds the supported size limit");
                }
                buffer.flip();
                digest.update(buffer.asReadOnlyBuffer());
                output.write(buffer.array(), buffer.position(), buffer.remaining());
                buffer.clear();
            }
            if (channel.size() != before.size()) {
                throw new IOException("JCEF distribution manifest changed while reading: " + path);
            }
        }
        BasicFileAttributes after = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (output.size() != before.size() || !sameFileSnapshot(before, after)) {
            throw new IOException("JCEF distribution manifest changed while reading: " + path);
        }
        return new ManifestBytes(output.toByteArray(), HexFormat.of().formatHex(digest.digest()));
    }

    private static boolean sameFileSnapshot(BasicFileAttributes before, BasicFileAttributes after) {
        Object beforeKey = before.fileKey();
        Object afterKey = after.fileKey();
        return after.isRegularFile() && before.size() == after.size() && before.lastModifiedTime().equals(after.lastModifiedTime()) && (beforeKey == null || afterKey == null || beforeKey.equals(afterKey));
    }

    private static String decodeUtf8(byte[] bytes) throws IOException {
        try {
            CharBuffer decoded = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes));
            return decoded.toString();
        } catch (CharacterCodingException failure) {
            throw new IOException("JCEF distribution manifest is not valid UTF-8", failure);
        }
    }

    private static MessageDigest newSha256() throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IOException("SHA-256 is not available", impossible);
        }
    }

    private record ManifestBytes(byte[] bytes, String sha256) {
    }

    /** Duplicate keys are rejected while parsing instead of being silently overwritten. */
    private static final class StrictParser {
        private final String json;
        private int position;
        private int elements;

        private StrictParser(String json) {
            this.json = json;
        }

        private ManifestData parse() throws IOException {
            Long manifestSchema = null;
            String archiveRoot = null;
            String cefApiVersion = null;
            String cefVersion = null;
            Long javaRelease = null;
            String javaCefCommit = null;
            Boolean joglSwingOsrSupported = null;
            List<String> jogampJars = null;
            List<String> jcefJars = null;
            List<String> distributionDirectories = null;
            List<ManifestFile> distributionFiles = null;
            List<String> runtimeEntries = null;
            List<ManifestFile> runtimeFiles = null;
            String target = null;
            Set<String> seen = new HashSet<>();

            expect('{');
            skipWhitespace();
            if (consume('}')) {
                throw error("JCEF distribution manifest is empty");
            }
            while (true) {
                String key = readString();
                countElement();
                if (!TOP_LEVEL_KEYS.contains(key) || !seen.add(key)) {
                    throw error("Unknown or duplicate JCEF distribution manifest key: " + key);
                }
                expect(':');
                switch (key) {
                    case "manifest_schema" -> manifestSchema = readInteger();
                    case "archive_root" -> archiveRoot = readString();
                    case "cef_api_version" -> cefApiVersion = readString();
                    case "cef_version" -> cefVersion = readString();
                    case "java_release" -> javaRelease = readInteger();
                    case "java_cef_commit" -> javaCefCommit = readString();
                    case "jogl_swing_osr_supported" -> joglSwingOsrSupported = readBoolean();
                    case "jogamp_jars" -> jogampJars = readStringArray(MAX_AUXILIARY_JARS);
                    case "jcef_jars" -> jcefJars = readStringArray(MAX_AUXILIARY_JARS);
                    case "distribution_directories" -> distributionDirectories = readStringArray(MCEFDistributionManifest.MAX_DISTRIBUTION_DIRECTORIES);
                    case "distribution_files" -> distributionFiles = readManifestFiles(MCEFDistributionManifest.MAX_DISTRIBUTION_FILES, "distribution");
                    case "runtime_entries" -> runtimeEntries = readStringArray(MCEFDistributionManifest.MAX_RUNTIME_ENTRIES);
                    case "runtime_files" -> runtimeFiles = readManifestFiles(MCEFDistributionManifest.MAX_RUNTIME_FILES, "runtime");
                    case "target" -> target = readString();
                    default -> throw new AssertionError(key);
                }
                skipWhitespace();
                if (consume('}')) {
                    break;
                }
                expect(',');
            }
            skipWhitespace();
            if (position != json.length()) {
                throw error("Trailing data after JCEF distribution manifest");
            }
            if (!seen.equals(TOP_LEVEL_KEYS)) {
                throw error("Incomplete JCEF distribution manifest");
            }
            if (manifestSchema < Integer.MIN_VALUE || manifestSchema > Integer.MAX_VALUE || javaRelease < Integer.MIN_VALUE || javaRelease > Integer.MAX_VALUE) {
                throw error("JCEF distribution manifest integer is outside the supported range");
            }
            return new ManifestData(manifestSchema.intValue(), archiveRoot, cefApiVersion, cefVersion, javaRelease.intValue(), javaCefCommit, joglSwingOsrSupported, List.copyOf(jogampJars), List.copyOf(jcefJars), List.copyOf(distributionDirectories), List.copyOf(distributionFiles), List.copyOf(runtimeEntries), List.copyOf(runtimeFiles), target);
        }

        private List<String> readStringArray(int maximumSize) throws IOException {
            List<String> values = new ArrayList<>();
            Set<String> unique = new HashSet<>();
            expect('[');
            skipWhitespace();
            if (consume(']')) {
                return values;
            }
            while (true) {
                if (values.size() >= maximumSize) {
                    throw error("JCEF distribution manifest array exceeds its supported limit");
                }
                String value = readString();
                countElement();
                if (!unique.add(value)) {
                    throw error("JCEF distribution manifest array contains a duplicate value");
                }
                values.add(value);
                skipWhitespace();
                if (consume(']')) {
                    return values;
                }
                expect(',');
            }
        }

        private List<ManifestFile> readManifestFiles(int maximumSize, String description) throws IOException {
            List<ManifestFile> files = new ArrayList<>();
            expect('[');
            skipWhitespace();
            if (consume(']')) {
                return files;
            }
            while (true) {
                if (files.size() >= maximumSize) {
                    throw error("JCEF " + description + " file inventory exceeds its supported limit");
                }
                files.add(readManifestFile(description));
                countElement();
                skipWhitespace();
                if (consume(']')) {
                    return files;
                }
                expect(',');
            }
        }

        private ManifestFile readManifestFile(String description) throws IOException {
            String path = null;
            Long size = null;
            String sha256 = null;
            Set<String> seen = new HashSet<>();
            expect('{');
            skipWhitespace();
            if (consume('}')) {
                throw error("Empty JCEF " + description + " file metadata");
            }
            while (true) {
                String key = readString();
                countElement();
                if (!FILE_KEYS.contains(key) || !seen.add(key)) {
                    throw error("Unknown or duplicate JCEF " + description + " file key: " + key);
                }
                expect(':');
                switch (key) {
                    case "path" -> path = readString();
                    case "size" -> size = readInteger();
                    case "sha256" -> sha256 = readString();
                    default -> throw new AssertionError(key);
                }
                skipWhitespace();
                if (consume('}')) {
                    break;
                }
                expect(',');
            }
            if (!seen.equals(FILE_KEYS)) {
                throw error("Incomplete JCEF " + description + " file metadata");
            }
            return new ManifestFile(path, size, sha256);
        }

        private String readString() throws IOException {
            skipWhitespace();
            if (!consume('"')) {
                throw error("Expected JSON string");
            }
            StringBuilder value = new StringBuilder();
            while (position < json.length()) {
                char character = json.charAt(position++);
                if (character == '"') {
                    return value.toString();
                }
                if (character == '\\') {
                    int escapedCharacter = readEscape(value);
                    if (escapedCharacter < 0) {
                        requireStringLimit(value);
                        continue;
                    }
                    character = (char) escapedCharacter;
                } else if (character < 0x20) {
                    throw error("Unescaped control character in JSON string");
                } else if (Character.isHighSurrogate(character)) {
                    if (position >= json.length() || !Character.isLowSurrogate(json.charAt(position))) {
                        throw error("Unpaired surrogate in JSON string");
                    }
                    value.append(character);
                    character = json.charAt(position++);
                } else if (Character.isLowSurrogate(character)) {
                    throw error("Unpaired surrogate in JSON string");
                }
                value.append(character);
                requireStringLimit(value);
            }
            throw error("Unterminated JSON string");
        }

        private int readEscape(StringBuilder value) throws IOException {
            if (position >= json.length()) {
                throw error("Unterminated JSON escape");
            }
            char escaped = json.charAt(position++);
            return switch (escaped) {
                case '"', '\\', '/' -> escaped;
                case 'b' -> '\b';
                case 'f' -> '\f';
                case 'n' -> '\n';
                case 'r' -> '\r';
                case 't' -> '\t';
                case 'u' -> readUnicodeEscape(value);
                default -> throw error("Invalid JSON escape");
            };
        }

        private int readUnicodeEscape(StringBuilder value) throws IOException {
            char first = readUnicodeUnit();
            if (Character.isHighSurrogate(first)) {
                if (position + 1 >= json.length() || json.charAt(position) != '\\' || json.charAt(position + 1) != 'u') {
                    throw error("Unpaired surrogate in JSON escape");
                }
                position += 2;
                char second = readUnicodeUnit();
                if (!Character.isLowSurrogate(second)) {
                    throw error("Unpaired surrogate in JSON escape");
                }
                value.append(first).append(second);
                return -1;
            }
            if (Character.isLowSurrogate(first)) {
                throw error("Unpaired surrogate in JSON escape");
            }
            return first;
        }

        private char readUnicodeUnit() throws IOException {
            if (position + 4 > json.length()) {
                throw error("Incomplete JSON unicode escape");
            }
            int value = 0;
            for (int index = 0; index < 4; index++) {
                int digit = Character.digit(json.charAt(position++), 16);
                if (digit < 0) {
                    throw error("Invalid JSON unicode escape");
                }
                value = (value << 4) | digit;
            }
            return (char) value;
        }

        private long readInteger() throws IOException {
            skipWhitespace();
            int start = position;
            if (position < json.length() && json.charAt(position) == '-') {
                position++;
            }
            if (position >= json.length() || !Character.isDigit(json.charAt(position))) {
                throw error("Expected JSON integer");
            }
            if (json.charAt(position) == '0' && position + 1 < json.length() && Character.isDigit(json.charAt(position + 1))) {
                throw error("JSON integer has a leading zero");
            }
            while (position < json.length() && Character.isDigit(json.charAt(position))) {
                position++;
            }
            if (position < json.length() && (json.charAt(position) == '.' || json.charAt(position) == 'e' || json.charAt(position) == 'E')) {
                throw error("Expected an integer, not a fractional JSON number");
            }
            try {
                return Long.parseLong(json.substring(start, position));
            } catch (NumberFormatException failure) {
                throw error("JSON integer is outside the supported range");
            }
        }

        private boolean readBoolean() throws IOException {
            skipWhitespace();
            if (json.startsWith("true", position)) {
                position += 4;
                return true;
            }
            if (json.startsWith("false", position)) {
                position += 5;
                return false;
            }
            throw error("Expected JSON boolean");
        }

        private void requireStringLimit(StringBuilder value) throws IOException {
            if (value.length() > MAX_JSON_STRING_CHARS) {
                throw error("JSON string exceeds the supported length limit");
            }
        }

        private void expect(char expected) throws IOException {
            skipWhitespace();
            if (!consume(expected)) {
                throw error("Expected '" + expected + "'");
            }
        }

        private boolean consume(char expected) {
            if (position < json.length() && json.charAt(position) == expected) {
                position++;
                return true;
            }
            return false;
        }

        private void skipWhitespace() {
            while (position < json.length()) {
                char character = json.charAt(position);
                if (character != ' ' && character != '\n' && character != '\r' && character != '\t') {
                    return;
                }
                position++;
            }
        }

        private void countElement() throws IOException {
            elements++;
            if (elements > MAX_JSON_ELEMENTS) {
                throw error("JCEF distribution manifest contains too many elements");
            }
        }

        private IOException error(String message) {
            return new IOException(message + " at character " + position);
        }
    }
}
