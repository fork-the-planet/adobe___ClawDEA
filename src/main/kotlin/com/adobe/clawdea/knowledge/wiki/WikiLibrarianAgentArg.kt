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

import com.google.gson.JsonObject
import com.google.gson.JsonArray

/**
 * Reads the bundled `wiki-librarian` agent definition from the plugin
 * classpath and produces the JSON payload accepted by Claude Code's
 * `--agents <json>` CLI flag.
 *
 * Claude Code 2.1.x scans `~/.claude/agents/` and the plugin marketplace
 * dirs for subagents, but does **not** scan `<project>/.claude/agents/`.
 * `--agents` is the project-scoped injection path.
 *
 * Frontmatter format (resource at `/agents/wiki-librarian.md`):
 *   ---
 *   name: <id>
 *   description: <single line>
 *   tools: <csv of tool names>     # optional
 *   ---
 *   <prompt body>
 *
 * Multiline YAML scalars (`description: |`) are intentionally NOT supported
 * by the parser here — keep the resource flat to match the working format
 * observed in user-level agent files.
 */
object WikiLibrarianAgentArg {

    private const val RESOURCE_PATH = "/agents/wiki-librarian.md"

    /**
     * Returns the JSON string for `--agents`, e.g.
     * `{"wiki-librarian":{"description":"...","prompt":"...","tools":[...]}}`.
     *
     * Throws [IllegalStateException] when the resource is missing or
     * malformed; callers should treat that as a packaging defect.
     */
    fun buildJson(): String {
        val raw = WikiLibrarianAgentArg::class.java.getResourceAsStream(RESOURCE_PATH)
            ?: throw IllegalStateException("Plugin resource not found: $RESOURCE_PATH")
        val text = raw.bufferedReader().use { it.readText() }
        val parsed = parse(text)
        val inner = JsonObject().apply {
            addProperty("description", parsed.description)
            addProperty("prompt", parsed.body)
            if (parsed.tools.isNotEmpty()) {
                add("tools", JsonArray().apply { parsed.tools.forEach { add(it) } })
            }
        }
        return JsonObject().apply { add(parsed.name, inner) }.toString()
    }

    internal data class Parsed(
        val name: String,
        val description: String,
        val tools: List<String>,
        val body: String,
    )

    internal fun parse(text: String): Parsed {
        val lines = text.lines()
        require(lines.isNotEmpty() && lines[0].trim() == "---") {
            "Agent file must start with YAML frontmatter delimiter '---'"
        }
        var endFrontmatter = -1
        for (i in 1 until lines.size) {
            if (lines[i].trim() == "---") { endFrontmatter = i; break }
        }
        require(endFrontmatter > 0) { "Agent file frontmatter has no closing '---'" }

        var name: String? = null
        var description: String? = null
        var tools = emptyList<String>()
        for (i in 1 until endFrontmatter) {
            val line = lines[i]
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val key = line.substring(0, colon).trim()
            val value = line.substring(colon + 1).trim()
            when (key) {
                "name" -> name = value
                "description" -> description = value
                "tools" -> tools = value.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            }
        }
        requireNotNull(name) { "Agent file missing 'name' in frontmatter" }
        requireNotNull(description) { "Agent file missing 'description' in frontmatter" }
        require(name!!.isNotBlank()) { "Agent 'name' is blank" }
        require(description!!.isNotBlank()) { "Agent 'description' is blank" }

        val body = lines.drop(endFrontmatter + 1).joinToString("\n").trim()
        require(body.isNotEmpty()) { "Agent file body is empty" }

        return Parsed(name = name!!, description = description!!, tools = tools, body = body)
    }
}
