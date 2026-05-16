/*
 * Copyright 2026 Adobe. All rights reserved.
 * This file is licensed to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy
 * of the License at http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under
 * the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS
 * OF ANY KIND, either express or implied. See the License for the specific language
 * governing permissions and limitations under the License.
 */
package com.adobe.clawdea.knowledge.wiki

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class WikiLibrarianInstallerTest {

    @Test fun `ensureInstalled writes the resource when the file is missing`() {
        val claudeDir = Files.createTempDirectory("clawdea-install")
        val installer = WikiLibrarianInstaller()
        val result = installer.ensureInstalled(claudeDir)
        assertTrue(result is WikiLibrarianInstaller.InstallResult.Installed)
        val written = claudeDir.resolve("agents").resolve("wiki-librarian.md")
        assertTrue(Files.exists(written))
        val content = Files.readString(written)
        assertTrue(content.startsWith("---"))
        assertTrue(content.contains("name: wiki-librarian"))
        assertTrue(content.contains("mcp__clawdea-intellij__record_wiki_suggestion"))
    }

    @Test fun `ensureInstalled no-ops when file already present`() {
        val claudeDir = Files.createTempDirectory("clawdea-install")
        val agentsDir = claudeDir.resolve("agents")
        Files.createDirectories(agentsDir)
        Files.writeString(agentsDir.resolve("wiki-librarian.md"), "user-managed content")
        val installer = WikiLibrarianInstaller()
        val result = installer.ensureInstalled(claudeDir)
        assertTrue(result is WikiLibrarianInstaller.InstallResult.AlreadyPresent)
        assertEquals(
            "user-managed content",
            Files.readString(agentsDir.resolve("wiki-librarian.md")),
        )
    }

    @Test fun `ensureInstalled written content equals plugin resource`() {
        val claudeDir = Files.createTempDirectory("clawdea-install")
        WikiLibrarianInstaller().ensureInstalled(claudeDir)
        val written = Files.readString(claudeDir.resolve("agents").resolve("wiki-librarian.md"))
        val resource = WikiLibrarianInstaller::class.java
            .getResourceAsStream("/agents/wiki-librarian.md")!!
            .bufferedReader().use { it.readText() }
        assertEquals(resource, written)
    }
}
