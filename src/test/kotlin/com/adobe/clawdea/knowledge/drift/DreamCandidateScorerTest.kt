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
import org.junit.Test

class DreamCandidateScorerTest {

    @Test fun `drops low confidence add-context candidates`() {
        val kept = candidate(
            kind = DreamCandidateKind.STALE_CONCEPT,
            contextCost = DreamContextCost.NEUTRAL,
            confidence = DreamConfidence.LOW,
        )
        val dropped = candidate(
            kind = DreamCandidateKind.MISSING_CONCEPT,
            contextCost = DreamContextCost.ADDS_CONTEXT,
            confidence = DreamConfidence.LOW,
            evidence = listOf(evidence("one"), evidence("two")),
        )

        assertEquals(listOf(kept), DreamCandidateScorer.filterAndRank(listOf(dropped, kept)))
    }

    @Test fun `drops add-context candidates with fewer than 2 evidence entries`() {
        val kept = candidate(
            kind = DreamCandidateKind.MISSING_CONCEPT,
            contextCost = DreamContextCost.ADDS_CONTEXT,
            confidence = DreamConfidence.HIGH,
            evidence = listOf(evidence("one"), evidence("two")),
        )
        val dropped = candidate(
            kind = DreamCandidateKind.MISSING_CONCEPT,
            contextCost = DreamContextCost.ADDS_CONTEXT,
            confidence = DreamConfidence.HIGH,
            evidence = listOf(evidence("one")),
        )

        assertEquals(listOf(kept), DreamCandidateScorer.filterAndRank(listOf(dropped, kept)))
    }

    @Test fun `prefers index cleanup and shrinking context before new pages`() {
        val newPage = candidate(
            kind = DreamCandidateKind.MISSING_CONCEPT,
            contextCost = DreamContextCost.ADDS_CONTEXT,
            confidence = DreamConfidence.HIGH,
            title = "Create concept",
            evidence = listOf(evidence("one"), evidence("two")),
        )
        val linkFix = candidate(
            kind = DreamCandidateKind.LINK_NORMALIZATION,
            contextCost = DreamContextCost.NEUTRAL,
            confidence = DreamConfidence.HIGH,
            title = "Normalize link",
        )
        val cleanup = candidate(
            kind = DreamCandidateKind.INDEX_CLEANUP,
            contextCost = DreamContextCost.SHRINKS_CONTEXT,
            confidence = DreamConfidence.MEDIUM,
            title = "Clean index",
        )

        assertEquals(listOf(cleanup, linkFix, newPage), DreamCandidateScorer.filterAndRank(listOf(newPage, linkFix, cleanup)))
    }

    private fun candidate(
        kind: DreamCandidateKind,
        contextCost: DreamContextCost,
        confidence: DreamConfidence,
        title: String = kind.name,
        evidence: List<DreamEvidence> = listOf(evidence("one")),
    ): DreamCandidate = DreamCandidate(
        kind = kind,
        title = title,
        targetFiles = listOf(".claude/wiki/index.md"),
        evidence = evidence,
        usefulness = "Useful candidate.",
        contextCost = contextCost,
        confidence = confidence,
        proposedAction = DreamProposedAction.REPORT_ONLY,
        patchPlan = "Report the candidate.",
    )

    private fun evidence(ref: String): DreamEvidence = DreamEvidence(
        type = DreamEvidenceType.SESSION_SIGNAL,
        ref = ref,
        summary = "Observed signal.",
    )
}
