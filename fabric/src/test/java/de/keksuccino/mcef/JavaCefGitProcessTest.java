/*
 *     MCEF (Minecraft Chromium Embedded Framework)
 *     Copyright (C) 2023 CinemaMod Group
 *
 *     This library is free software; you can redistribute it and/or
 *     modify it under the terms of the GNU Lesser General Public
 *     License as published by the Free Software Foundation; either
 *     version 2.1 of the License, or (at your option) any later version.
 *
 *     This library is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *     Lesser General Public License for more details.
 *
 *     You should have received a copy of the GNU Lesser General Public
 *     License along with this library; if not, write to the Free Software
 *     Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301
 *     USA
 */

package de.keksuccino.mcef;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaCefGitProcessTest {
    @TempDir
    Path repository;

    @Test
    void repeatedTextAndBinaryGitCommandsDrainWithoutHanging() {
        assertTimeoutPreemptively(Duration.ofSeconds(30), this::runRepeatedGitCommands);
    }

    private void runRepeatedGitCommands() throws IOException {
        JavaCefCommitResolver.runGit(repository, List.of("init", "--quiet"), true);
        for (int i = 0; i < 24; i++) {
            JavaCefCommitResolver.GitResult textResult = JavaCefCommitResolver.runGit(repository, List.of("rev-parse", "--git-dir"), true);
            JavaCefCommitResolver.GitBinaryResult binaryResult = JavaCefCommitResolver.runGitBinary(repository, List.of("hash-object", "--stdin"), "mcef".getBytes(StandardCharsets.UTF_8), true, Map.of(), 128);
            assertEquals(".git", textResult.output().trim());
            assertTrue(binaryResult.output().length > 0);
        }
    }
}
