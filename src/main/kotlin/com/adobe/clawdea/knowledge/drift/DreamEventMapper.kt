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
        // TODO(Task 12): this file is scheduled for deletion. The body is
        // stubbed because the Dream* DriftEvent variants no longer exist;
        // callers will be removed alongside the file.
        throw UnsupportedOperationException("DreamEventMapper is deprecated; pending removal in Task 12.")
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
