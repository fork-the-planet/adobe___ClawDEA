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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path

class DreamEventMapperTest {

    @Test fun `maps linkNormalization applyLowRisk to auto-applicable event`() {
        val projectRoot = Path.of("/repo")
        val candidate = candidate(
            kind = DreamCandidateKind.LINK_NORMALIZATION,
            title = "Normalize rollout link",
            targetFiles = listOf(".claude/wiki/index.md"),
            contextCost = DreamContextCost.NEUTRAL,
            confidence = DreamConfidence.HIGH,
            proposedAction = DreamProposedAction.APPLY_LOW_RISK,
        )

        val event = DreamEventMapper.toEvent(projectRoot, candidate)

        assertTrue(event is DriftEvent.DreamLinkNormalization)
        event as DriftEvent.DreamLinkNormalization
        assertEquals(projectRoot.resolve(".claude/wiki/index.md").normalize(), event.targetFile)
        assertEquals("Normalize rollout link", event.title)
        assertEquals("Replace one old wikilink.", event.patchPlan)
        assertTrue(event.autoApplicable)
        assertEquals(
            "dream-link-normalization:kind=LINK_NORMALIZATION;" +
                "targets=.claude/wiki/index.md;" +
                "action=APPLY_LOW_RISK;" +
                "cost=NEUTRAL;" +
                "evidence=STALE_LINK:.claude/wiki/index.md",
            event.signature,
        )
    }

    @Test fun `maps missingConcept to missing concept event`() {
        val projectRoot = Path.of("/repo")
        val targetFile = projectRoot.resolve(".claude/wiki/concepts/rollout-flow.md").normalize()
        val candidate = candidate(
            kind = DreamCandidateKind.MISSING_CONCEPT,
            title = "Add rollout concept",
            targetFiles = listOf(".claude/wiki/concepts/rollout-flow.md"),
            contextCost = DreamContextCost.ADDS_CONTEXT,
            confidence = DreamConfidence.MEDIUM,
            proposedAction = DreamProposedAction.PROPOSE_DIFF,
        )

        val event = DreamEventMapper.toEvent(projectRoot, candidate)

        assertTrue(event is DriftEvent.DreamMissingConcept)
        event as DriftEvent.DreamMissingConcept
        assertEquals(targetFile, event.targetFile)
        assertEquals("Add rollout concept", event.title)
        assertEquals(
            "dream-missing-concept:kind=MISSING_CONCEPT;" +
                "targets=.claude/wiki/concepts/rollout-flow.md;" +
                "action=PROPOSE_DIFF;" +
                "cost=ADDS_CONTEXT;" +
                "evidence=STALE_LINK:.claude/wiki/concepts/rollout-flow.md",
            event.signature,
        )
    }

    @Test fun `title and project root changes do not change signature`() {
        val first = DreamEventMapper.toEvent(
            Path.of("/repo-one"),
            candidate(
                kind = DreamCandidateKind.LINK_NORMALIZATION,
                title = "Normalize rollout link",
                targetFiles = listOf(".claude/wiki/index.md"),
                contextCost = DreamContextCost.NEUTRAL,
                confidence = DreamConfidence.HIGH,
                proposedAction = DreamProposedAction.APPLY_LOW_RISK,
            ),
        )
        val second = DreamEventMapper.toEvent(
            Path.of("/repo-two"),
            candidate(
                kind = DreamCandidateKind.LINK_NORMALIZATION,
                title = "Make rollout link markdown",
                targetFiles = listOf(".claude/wiki/index.md"),
                contextCost = DreamContextCost.NEUTRAL,
                confidence = DreamConfidence.HIGH,
                proposedAction = DreamProposedAction.APPLY_LOW_RISK,
            ),
        )

        assertEquals(first.signature, second.signature)
    }

    @Test fun `sorts evidence when building signature`() {
        val candidate = candidate(
            kind = DreamCandidateKind.LINK_NORMALIZATION,
            title = "Normalize rollout link",
            targetFiles = listOf(".claude/wiki/index.md"),
            contextCost = DreamContextCost.NEUTRAL,
            confidence = DreamConfidence.HIGH,
            proposedAction = DreamProposedAction.APPLY_LOW_RISK,
            evidence = listOf(
                DreamEvidence(DreamEvidenceType.WIKI_PROBE_MISS, ".claude/wiki/index.md#b", "second"),
                DreamEvidence(DreamEvidenceType.STALE_LINK, ".claude/wiki/index.md#a", "first"),
            ),
        )

        val event = DreamEventMapper.toEvent(Path.of("/repo"), candidate)

        assertEquals(
            "dream-link-normalization:kind=LINK_NORMALIZATION;" +
                "targets=.claude/wiki/index.md;" +
                "action=APPLY_LOW_RISK;" +
                "cost=NEUTRAL;" +
                "evidence=STALE_LINK:.claude/wiki/index.md#a|WIKI_PROBE_MISS:.claude/wiki/index.md#b",
            event.signature,
        )
    }

    @Test fun `normalizes target and evidence paths in signature`() {
        val candidate = candidate(
            kind = DreamCandidateKind.LINK_NORMALIZATION,
            title = "Normalize rollout link",
            targetFiles = listOf("./.claude\\wiki/./index.md"),
            contextCost = DreamContextCost.NEUTRAL,
            confidence = DreamConfidence.HIGH,
            proposedAction = DreamProposedAction.APPLY_LOW_RISK,
            evidence = listOf(DreamEvidence(DreamEvidenceType.STALE_LINK, "./.claude\\wiki/./index.md", "old wikilink")),
        )

        val event = DreamEventMapper.toEvent(Path.of("/repo"), candidate)

        assertEquals(
            "dream-link-normalization:kind=LINK_NORMALIZATION;" +
                "targets=.claude/wiki/index.md;" +
                "action=APPLY_LOW_RISK;" +
                "cost=NEUTRAL;" +
                "evidence=STALE_LINK:.claude/wiki/index.md",
            event.signature,
        )
    }

    @Test fun `indexCleanup applyLowRisk high confidence is not autoApplicable`() {
        val event = DreamEventMapper.toEvent(
            Path.of("/repo"),
            candidate(
                kind = DreamCandidateKind.INDEX_CLEANUP,
                title = "Tighten index",
                targetFiles = listOf(".claude/wiki/index.md"),
                contextCost = DreamContextCost.SHRINKS_CONTEXT,
                confidence = DreamConfidence.HIGH,
                proposedAction = DreamProposedAction.APPLY_LOW_RISK,
            ),
        )

        assertTrue(event is DriftEvent.DreamIndexCleanup)
        event as DriftEvent.DreamIndexCleanup
        assertFalse(event.autoApplicable)
    }

    @Test fun `linkNormalization with multiple target files is not autoApplicable`() {
        val event = DreamEventMapper.toEvent(
            Path.of("/repo"),
            candidate(
                kind = DreamCandidateKind.LINK_NORMALIZATION,
                title = "Normalize rollout links",
                targetFiles = listOf(".claude/wiki/index.md", ".claude/wiki/concepts/rollout-flow.md"),
                contextCost = DreamContextCost.NEUTRAL,
                confidence = DreamConfidence.HIGH,
                proposedAction = DreamProposedAction.APPLY_LOW_RISK,
            ),
        )

        assertTrue(event is DriftEvent.DreamLinkNormalization)
        event as DriftEvent.DreamLinkNormalization
        assertFalse(event.autoApplicable)
    }

    private fun candidate(
        kind: DreamCandidateKind,
        title: String,
        targetFiles: List<String>,
        contextCost: DreamContextCost,
        confidence: DreamConfidence,
        proposedAction: DreamProposedAction,
        evidence: List<DreamEvidence> = listOf(DreamEvidence(DreamEvidenceType.STALE_LINK, targetFiles.first(), "old wikilink")),
    ): DreamCandidate = DreamCandidate(
        kind = kind,
        title = title,
        targetFiles = targetFiles,
        evidence = evidence,
        usefulness = "Keeps wiki references parseable.",
        contextCost = contextCost,
        confidence = confidence,
        proposedAction = proposedAction,
        patchPlan = "Replace one old wikilink.",
    )
}
