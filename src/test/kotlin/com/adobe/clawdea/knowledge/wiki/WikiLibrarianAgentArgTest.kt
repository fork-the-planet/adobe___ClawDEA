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

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class WikiLibrarianAgentArgTest {

    @Test fun `parse extracts name description tools and body`() {
        val text = """
            |---
            |name: my-agent
            |description: One line description here.
            |tools: Read, mcp__x__find_symbol, mcp__x__read_file
            |---
            |
            |Body line one.
            |Body line two.
        """.trimMargin()
        val parsed = WikiLibrarianAgentArg.parse(text)
        assertEquals("my-agent", parsed.name)
        assertEquals("One line description here.", parsed.description)
        assertEquals(listOf("Read", "mcp__x__find_symbol", "mcp__x__read_file"), parsed.tools)
        assertEquals("Body line one.\nBody line two.", parsed.body)
    }

    @Test fun `parse allows missing tools field`() {
        val text = """
            |---
            |name: a
            |description: d
            |---
            |
            |body
        """.trimMargin()
        val parsed = WikiLibrarianAgentArg.parse(text)
        assertTrue(parsed.tools.isEmpty())
    }

    @Test fun `parse rejects missing opening delimiter`() {
        try {
            WikiLibrarianAgentArg.parse("name: x\n---\nbody")
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("frontmatter delimiter"))
        }
    }

    @Test fun `parse rejects missing closing delimiter`() {
        try {
            WikiLibrarianAgentArg.parse("---\nname: x\ndescription: d\n\nbody")
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("closing"))
        }
    }

    @Test fun `parse rejects missing name`() {
        try {
            WikiLibrarianAgentArg.parse("---\ndescription: d\n---\nbody")
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("'name'"))
        }
    }

    @Test fun `parse rejects empty body`() {
        try {
            WikiLibrarianAgentArg.parse("---\nname: a\ndescription: d\n---\n\n   ")
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("body"))
        }
    }

    @Test fun `buildJson reads the bundled resource and produces well-formed JSON`() {
        val json = WikiLibrarianAgentArg.buildJson()
        val root = JsonParser.parseString(json).asJsonObject
        assertTrue(root.has("wiki-librarian"))
        val inner = root.getAsJsonObject("wiki-librarian")
        assertTrue(inner.has("description"))
        assertTrue(inner.has("prompt"))
        assertTrue(inner.has("tools"))
        val tools = inner.getAsJsonArray("tools")
        val toolNames = (0 until tools.size()).map { tools[it].asString }
        assertTrue("Read" in toolNames)
        assertTrue("mcp__clawdea-intellij__record_wiki_suggestion" in toolNames)
        assertTrue("mcp__clawdea-intellij__read_wiki_page" in toolNames)
    }

    @Test fun `buildJson prompt body starts with the documented preamble`() {
        val json = WikiLibrarianAgentArg.buildJson()
        val prompt = JsonParser.parseString(json)
            .asJsonObject
            .getAsJsonObject("wiki-librarian")
            .get("prompt").asString
        assertTrue(prompt.startsWith("You are this project's **wiki librarian**"))
        assertTrue(prompt.contains("record_wiki_suggestion"))
    }
}
