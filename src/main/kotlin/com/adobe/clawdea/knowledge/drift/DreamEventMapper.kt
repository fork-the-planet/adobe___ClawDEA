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

import java.nio.file.Path

object DreamEventMapper {

    fun toEvent(projectRoot: Path, candidate: DreamCandidate): DriftEvent {
        val targetFile = projectRoot.resolve(candidate.targetFiles.first()).normalize()
        val autoApplicable = isAutoApplicable(candidate)
        val signatureKey = signatureKey(candidate)

        return when (candidate.kind) {
            DreamCandidateKind.INDEX_CLEANUP -> DriftEvent.DreamIndexCleanup(
                targetFile = targetFile,
                title = candidate.title,
                patchPlan = candidate.patchPlan,
                autoApplicable = autoApplicable,
                signatureKey = signatureKey,
            )
            DreamCandidateKind.LINK_NORMALIZATION -> DriftEvent.DreamLinkNormalization(
                targetFile = targetFile,
                title = candidate.title,
                patchPlan = candidate.patchPlan,
                autoApplicable = autoApplicable,
                signatureKey = signatureKey,
            )
            DreamCandidateKind.SOURCE_REFERENCE_FIX -> DriftEvent.DreamSourceReferenceFix(
                targetFile = targetFile,
                title = candidate.title,
                patchPlan = candidate.patchPlan,
                autoApplicable = autoApplicable,
                signatureKey = signatureKey,
            )
            DreamCandidateKind.DUPLICATE_CONCEPT -> DriftEvent.DreamDuplicateConcept(
                targetFile = targetFile,
                title = candidate.title,
                patchPlan = candidate.patchPlan,
                signatureKey = signatureKey,
            )
            DreamCandidateKind.STALE_CONCEPT -> DriftEvent.DreamStaleConcept(
                targetFile = targetFile,
                title = candidate.title,
                patchPlan = candidate.patchPlan,
                signatureKey = signatureKey,
            )
            DreamCandidateKind.MISSING_CONCEPT -> DriftEvent.DreamMissingConcept(
                targetFile = targetFile,
                title = candidate.title,
                patchPlan = candidate.patchPlan,
                signatureKey = signatureKey,
            )
        }
    }

    private fun isAutoApplicable(candidate: DreamCandidate): Boolean =
        candidate.kind == DreamCandidateKind.LINK_NORMALIZATION &&
            candidate.proposedAction == DreamProposedAction.APPLY_LOW_RISK &&
            candidate.confidence == DreamConfidence.HIGH &&
            candidate.contextCost != DreamContextCost.ADDS_CONTEXT &&
            candidate.targetFiles.size == 1

    private fun signatureKey(candidate: DreamCandidate): String {
        val targets = candidate.targetFiles
            .map(::normalizeRelativeRef)
            .joinToString(",")
        val evidence = candidate.evidence
            .map { "${it.type.name}:${normalizeRelativeRef(it.ref)}" }
            .sorted()
            .joinToString("|")
        return "kind=${candidate.kind.name};" +
            "targets=$targets;" +
            "action=${candidate.proposedAction.name};" +
            "cost=${candidate.contextCost.name};" +
            "evidence=$evidence"
    }

    private fun normalizeRelativeRef(value: String): String {
        val cleaned = value.trim().replace('\\', '/')
        val fragment = cleaned.substringAfter('#', missingDelimiterValue = "")
        val path = cleaned.substringBefore('#')
        val normalizedPath = normalizePathSegments(path)
        return if (fragment.isBlank()) normalizedPath else "$normalizedPath#$fragment"
    }

    private fun normalizePathSegments(path: String): String {
        val segments = ArrayDeque<String>()
        for (segment in path.split('/')) {
            when {
                segment.isBlank() || segment == "." -> Unit
                segment == ".." && segments.isNotEmpty() -> segments.removeLast()
                segment == ".." -> Unit
                else -> segments.addLast(segment)
            }
        }
        return segments.joinToString("/")
    }
}
