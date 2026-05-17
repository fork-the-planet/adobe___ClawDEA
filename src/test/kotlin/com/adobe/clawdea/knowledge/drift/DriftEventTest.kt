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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.nio.file.Paths

class DriftEventTest {

    @Test fun `CommitDrift signature stable across instances with same fields`() {
        val a = DriftEvent.CommitDrift(
            wikiPage = Paths.get(".claude/wiki/concepts/wiki-librarian.md"),
            commitShas = listOf("abc123", "def456"),
            touchedPaths = listOf("src/main/kotlin/Foo.kt"),
            firstObservedAt = "2026-05-17T16:30:00Z",
        )
        val b = DriftEvent.CommitDrift(
            wikiPage = Paths.get(".claude/wiki/concepts/wiki-librarian.md"),
            commitShas = listOf("abc123", "def456"),
            touchedPaths = listOf("src/main/kotlin/Foo.kt"),
            firstObservedAt = "2026-05-17T17:00:00Z",  // different — not in signature
        )
        assertEquals(a.signature, b.signature)
    }

    @Test fun `CommitDrift signature differs across pages`() {
        val a = DriftEvent.CommitDrift(
            wikiPage = Paths.get(".claude/wiki/concepts/page-a.md"),
            commitShas = listOf("abc123"),
            touchedPaths = listOf("src/main/kotlin/Foo.kt"),
            firstObservedAt = "2026-05-17T16:30:00Z",
        )
        val b = a.copy(wikiPage = Paths.get(".claude/wiki/concepts/page-b.md"))
        assertNotEquals(a.signature, b.signature)
    }

    @Test fun `CommitDrift signature differs across commit batches`() {
        val a = DriftEvent.CommitDrift(
            wikiPage = Paths.get(".claude/wiki/concepts/x.md"),
            commitShas = listOf("abc123"),
            touchedPaths = listOf("src/main/kotlin/Foo.kt"),
            firstObservedAt = "2026-05-17T16:30:00Z",
        )
        val b = a.copy(commitShas = listOf("abc123", "def456"))
        assertNotEquals(a.signature, b.signature)
    }

    @Test fun `CommitDrift signature contains the page basename and is bounded`() {
        val event = DriftEvent.CommitDrift(
            wikiPage = Paths.get(".claude/wiki/concepts/wiki-librarian.md"),
            commitShas = List(50) { "%07x".format(it) },  // very long
            touchedPaths = emptyList(),
            firstObservedAt = "2026-05-17T16:30:00Z",
        )
        // basename appears so the signature is human-readable in the dismissed list
        assertEquals(true, event.signature.contains("wiki-librarian.md"))
        // 80-char cap on the commit-list portion (signature itself is longer than 80)
        val commitsPortion = event.signature.substringAfterLast(":")
        assertEquals(true, commitsPortion.length <= 80)
    }

    @Test fun `WikiSuggestion signature stable for same (kind, primaryTarget)`() {
        val a = DriftEvent.WikiSuggestion(
            kind = SuggestionKind.missingConcept,
            title = "t1",
            rationale = "r1",
            targetFiles = listOf(".claude/wiki/concepts/foo.md", ".claude/wiki/index.md"),
            sourcePage = null,
            recordedAt = "2026-05-17T16:30:00Z",
        )
        val b = a.copy(title = "t2", rationale = "r2", recordedAt = "2026-05-17T18:00:00Z")
        assertEquals(a.signature, b.signature)
    }
}
