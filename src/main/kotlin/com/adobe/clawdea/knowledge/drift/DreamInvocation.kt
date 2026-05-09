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
package com.adobe.clawdea.knowledge.drift

import com.adobe.clawdea.cli.resolveClaudeCliPath
import com.adobe.clawdea.settings.ClawDEASettings
import java.io.BufferedReader
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.TimeUnit

sealed class DreamInvocationResult {
    data class Available(val json: String) : DreamInvocationResult()
    data class Unavailable(val reason: String) : DreamInvocationResult()
}

interface DreamInvocation {
    fun run(projectRoot: Path, prompt: String): DreamInvocationResult
}

class ClaudeDreamInvocation(
    private val timeoutSeconds: Long = 120,
) : DreamInvocation {

    override fun run(projectRoot: Path, prompt: String): DreamInvocationResult {
        val cliPath = resolveClaudeCliPath(ClawDEASettings.getInstance().state.cliPath)
        val command = listOf(
            cliPath,
            "-p",
            "--output-format",
            "text",
            "--no-session-persistence",
            prompt,
        )

        return try {
            val process = ProcessBuilder(command)
                .directory(projectRoot.toFile())
                .redirectErrorStream(false)
                .start()
            val stdout = StringBuilder()
            val stderr = StringBuilder()
            val stdoutThread = drain(process.inputStream.bufferedReader(StandardCharsets.UTF_8), stdout)
            val stderrThread = drain(process.errorStream.bufferedReader(StandardCharsets.UTF_8), stderr)

            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                stdoutThread.join(500)
                stderrThread.join(500)
                return DreamInvocationResult.Unavailable("Dreams timed out")
            }

            stdoutThread.join(500)
            stderrThread.join(500)
            if (process.exitValue() == 0) {
                DreamInvocationResult.Available(stdout.toString().trim())
            } else {
                val detail = stderr.toString()
                    .lines()
                    .filter { it.isNotBlank() }
                    .takeLast(5)
                    .joinToString("\n")
                    .ifBlank { "Dreams failed with exit code ${process.exitValue()}" }
                DreamInvocationResult.Unavailable(detail)
            }
        } catch (e: Exception) {
            DreamInvocationResult.Unavailable(e.message?.takeIf { it.isNotBlank() } ?: "Dreams failed")
        }
    }

    private fun drain(reader: BufferedReader, output: StringBuilder): Thread =
        Thread {
            reader.useLines { lines ->
                for (line in lines) output.appendLine(line)
            }
        }.apply {
            isDaemon = true
            start()
        }
}
