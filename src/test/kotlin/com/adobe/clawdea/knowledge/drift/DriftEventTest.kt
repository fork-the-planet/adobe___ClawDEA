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

class DriftEventTest {

    @Test fun `WikiSuggestion signature uses non-index target as primary`() {
        val event = DriftEvent.WikiSuggestion(
            kind = SuggestionKind.missingConcept,
            title = "Add concept for FilesystemRefreshCoordinator",
            rationale = "Multiple subsystems reference it; no page exists.",
            targetFiles = listOf(
                ".claude/wiki/concepts/filesystem-refresh-coordinator.md",
                ".claude/wiki/index.md",
            ),
            sourcePage = null,
            recordedAt = "2026-05-16T16:30:00Z",
        )
        assertEquals(
            "wiki-suggestion:missingConcept:.claude/wiki/concepts/filesystem-refresh-coordinator.md",
            event.signature,
        )
    }

    @Test fun `WikiSuggestion signature falls back to first target when only index present`() {
        val event = DriftEvent.WikiSuggestion(
            kind = SuggestionKind.incompleteConcept,
            title = "Cover primer ordering on index",
            rationale = "Index lacks rationale.",
            targetFiles = listOf(".claude/wiki/index.md"),
            sourcePage = ".claude/wiki/index.md",
            recordedAt = "2026-05-16T16:30:00Z",
        )
        assertEquals(
            "wiki-suggestion:incompleteConcept:.claude/wiki/index.md",
            event.signature,
        )
    }

    @Test fun `WikiSuggestion signature differs across kinds for same target`() {
        val target = ".claude/wiki/concepts/primer.md"
        val missing = DriftEvent.WikiSuggestion(
            kind = SuggestionKind.missingConcept,
            title = "x",
            rationale = "y reason",
            targetFiles = listOf(target),
            sourcePage = null,
            recordedAt = "2026-05-16T16:30:00Z",
        )
        val stale = missing.copy(kind = SuggestionKind.staleConcept)
        assertNotEquals(missing.signature, stale.signature)
    }

    @Test fun `WikiSuggestion signature is stable for same kind plus primary target`() {
        val a = DriftEvent.WikiSuggestion(
            kind = SuggestionKind.staleConcept,
            title = "first title",
            rationale = "first rationale",
            targetFiles = listOf(".claude/wiki/concepts/cli-bridge.md"),
            sourcePage = ".claude/wiki/concepts/cli-bridge.md",
            recordedAt = "2026-05-16T10:00:00Z",
        )
        val b = a.copy(
            title = "different title",
            rationale = "different rationale",
            sourcePage = null,
            recordedAt = "2026-05-16T18:00:00Z",
        )
        assertEquals(a.signature, b.signature)
    }
}
