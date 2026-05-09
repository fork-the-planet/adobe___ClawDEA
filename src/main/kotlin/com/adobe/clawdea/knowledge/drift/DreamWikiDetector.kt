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

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

data class DreamWikiSettings(
    val enabled: Boolean,
    val minElapsedHours: Int = 24,
    val minSignalUnits: Int = 5,
    val scanThrottleMinutes: Int = 10,
)

data class DreamDetectionResult(
    val events: List<DriftEvent>,
    val status: String,
    val filteredCandidateCount: Int,
)

class DreamWikiDetector(
    private val invocation: DreamInvocation = ClaudeDreamInvocation(),
) {

    fun detect(
        projectRoot: Path,
        state: DriftState,
        settings: DreamWikiSettings,
        now: Instant,
        force: Boolean,
        activeTurn: Boolean,
    ): DreamDetectionResult {
        if (!force) {
            val decision = DreamDueGate.evaluate(
                enabled = settings.enabled,
                now = now,
                state = state,
                minElapsedHours = settings.minElapsedHours,
                minSignalUnits = settings.minSignalUnits,
                scanThrottleMinutes = settings.scanThrottleMinutes,
                activeTurn = activeTurn,
                lockHeld = state.dreamLockOwner.isNotBlank(),
            )
            if (!decision.due) {
                return DreamDetectionResult(
                    events = emptyList(),
                    status = "not-due:${decision.reasons.joinToString(",")}",
                    filteredCandidateCount = 0,
                )
            }
        }

        return when (val result = invocation.run(projectRoot, buildPrompt(projectRoot))) {
            is DreamInvocationResult.Unavailable -> DreamDetectionResult(emptyList(), result.reason, 0)
            is DreamInvocationResult.Available -> validateScoreAndMap(projectRoot, result.json)
        }
    }

    private fun validateScoreAndMap(projectRoot: Path, json: String): DreamDetectionResult {
        val validation = DreamOutputValidator.validate(json)
        if (validation.errors.isNotEmpty()) {
            return DreamDetectionResult(
                events = emptyList(),
                status = "invalid:${validation.errors.joinToString("; ")}",
                filteredCandidateCount = 0,
            )
        }

        val scored = DreamCandidateScorer.filterAndRank(validation.candidates)
        return DreamDetectionResult(
            events = scored.map { DreamEventMapper.toEvent(projectRoot, it) },
            status = "ok",
            filteredCandidateCount = validation.candidates.size - scored.size,
        )
    }

    private fun buildPrompt(projectRoot: Path): String {
        val indexPath = projectRoot.resolve(".claude/wiki/index.md")
        val index = if (Files.isRegularFile(indexPath)) {
            Files.readString(indexPath).take(INDEX_CHAR_CAP)
        } else {
            ""
        }

        return """
            You are maintaining the ClawDEA project wiki for future LLM navigation.
            Return JSON only. Do not include Markdown fences or prose.
            Do not write files or modify the project.
            Prefer cleanup and link normalization over new pages.
            Only propose candidates with concrete evidence and clear future navigation value.

            Output schema:
            {"candidates":[{"kind":"missingConcept|staleConcept|duplicateConcept|indexCleanup|linkNormalization|sourceReferenceFix","title":"Short title","targetFiles":[".claude/wiki/index.md"],"evidence":[{"type":"sourceRef|sessionSignal|wikiProbeMiss|acceptedWikiChange|staleLink|duplicateContent","ref":"path or stable identifier","summary":"Why this evidence matters"}],"usefulness":"How this helps future LLM navigation","contextCost":"shrinks-context|neutral|adds-context","confidence":"high|medium|low","proposedAction":"applyLowRisk|proposeDiff|reportOnly","patchPlan":"Concise edit description, not executable code"}]}

            .claude/wiki/index.md:
            $index
        """.trimIndent()
    }

    private companion object {
        const val INDEX_CHAR_CAP = 11_500
    }
}
