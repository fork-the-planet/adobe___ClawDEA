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

import com.intellij.openapi.diagnostic.Logger
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Ensures `<claudeDir>/agents/wiki-librarian.md` exists. Canonical text is
 * shipped as a plugin resource at `/agents/wiki-librarian.md`. Policy:
 * write-if-missing, never overwrite. To pick up an updated agent file from
 * a newer plugin version, the user deletes the file and runs ClawDEA;
 * the next refresh reinstalls.
 */
class WikiLibrarianInstaller {

    sealed class InstallResult {
        object AlreadyPresent : InstallResult()
        object Installed : InstallResult()
        data class Failed(val cause: Throwable) : InstallResult()
    }

    fun ensureInstalled(claudeDir: Path): InstallResult {
        val target = claudeDir.resolve(AGENTS_DIR).resolve(AGENT_FILE)
        if (Files.exists(target)) return InstallResult.AlreadyPresent

        return try {
            val resource = WikiLibrarianInstaller::class.java
                .getResourceAsStream("/$AGENTS_DIR/$AGENT_FILE")
                ?: return InstallResult.Failed(
                    IllegalStateException("Plugin resource not found: /$AGENTS_DIR/$AGENT_FILE")
                )
            val text = resource.bufferedReader().use { it.readText() }
            val parent = target.parent
            Files.createDirectories(parent)
            val temp = Files.createTempFile(parent, AGENT_FILE + ".tmp", "")
            try {
                Files.writeString(temp, text)
                try {
                    Files.move(temp, target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING)
                } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                if (Files.exists(temp)) {
                    try { Files.deleteIfExists(temp) } catch (_: Exception) {}
                }
            }
            InstallResult.Installed
        } catch (e: Throwable) {
            LOG.warn("WikiLibrarianInstaller failed for $target: ${e.message}")
            InstallResult.Failed(e)
        }
    }

    companion object {
        private val LOG = Logger.getInstance(WikiLibrarianInstaller::class.java)
        private const val AGENTS_DIR = "agents"
        private const val AGENT_FILE = "wiki-librarian.md"
    }
}
