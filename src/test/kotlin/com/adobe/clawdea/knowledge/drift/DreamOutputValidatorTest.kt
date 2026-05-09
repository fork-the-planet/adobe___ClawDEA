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
import org.junit.Assert.assertTrue
import org.junit.Test

class DreamOutputValidatorTest {

    @Test fun `accepts candidate with supported kind and evidence`() {
        val result = DreamOutputValidator.validate(VALID_JSON)

        assertEquals(emptyList<String>(), result.errors)
        assertEquals(1, result.candidates.size)
        val candidate = result.candidates.single()
        assertEquals(DreamCandidateKind.LINK_NORMALIZATION, candidate.kind)
        assertEquals("Normalize rollout link", candidate.title)
        assertEquals(listOf(".claude/wiki/index.md"), candidate.targetFiles)
        assertEquals(DreamEvidenceType.STALE_LINK, candidate.evidence.single().type)
        assertEquals(DreamContextCost.NEUTRAL, candidate.contextCost)
        assertEquals(DreamConfidence.HIGH, candidate.confidence)
        assertEquals(DreamProposedAction.APPLY_LOW_RISK, candidate.proposedAction)
    }

    @Test fun `maps every supported kind wire value`() {
        val cases = listOf(
            "missingConcept" to DreamCandidateKind.MISSING_CONCEPT,
            "staleConcept" to DreamCandidateKind.STALE_CONCEPT,
            "duplicateConcept" to DreamCandidateKind.DUPLICATE_CONCEPT,
            "indexCleanup" to DreamCandidateKind.INDEX_CLEANUP,
            "linkNormalization" to DreamCandidateKind.LINK_NORMALIZATION,
            "sourceReferenceFix" to DreamCandidateKind.SOURCE_REFERENCE_FIX,
        )

        cases.forEach { (wireValue, expected) ->
            val result = DreamOutputValidator.validate(jsonWithCandidate(candidateJson(
                kind = wireValue,
                proposedAction = "reportOnly",
            )))

            assertEquals("Expected no errors for $wireValue", emptyList<String>(), result.errors)
            assertEquals(expected, result.candidates.single().kind)
        }
    }

    @Test fun `maps every supported evidence type wire value`() {
        val cases = listOf(
            "sourceRef" to DreamEvidenceType.SOURCE_REF,
            "sessionSignal" to DreamEvidenceType.SESSION_SIGNAL,
            "wikiProbeMiss" to DreamEvidenceType.WIKI_PROBE_MISS,
            "acceptedWikiChange" to DreamEvidenceType.ACCEPTED_WIKI_CHANGE,
            "staleLink" to DreamEvidenceType.STALE_LINK,
            "duplicateContent" to DreamEvidenceType.DUPLICATE_CONTENT,
        )

        cases.forEach { (wireValue, expected) ->
            val result = DreamOutputValidator.validate(jsonWithCandidate(candidateJson(
                evidence = evidenceJson(type = wireValue),
                proposedAction = "reportOnly",
            )))

            assertEquals("Expected no errors for $wireValue", emptyList<String>(), result.errors)
            assertEquals(expected, result.candidates.single().evidence.single().type)
        }
    }

    @Test fun `maps every supported context cost wire value`() {
        val cases = listOf(
            "shrinks-context" to DreamContextCost.SHRINKS_CONTEXT,
            "neutral" to DreamContextCost.NEUTRAL,
            "adds-context" to DreamContextCost.ADDS_CONTEXT,
        )

        cases.forEach { (wireValue, expected) ->
            val result = DreamOutputValidator.validate(jsonWithCandidate(candidateJson(
                contextCost = wireValue,
                proposedAction = "reportOnly",
            )))

            assertEquals("Expected no errors for $wireValue", emptyList<String>(), result.errors)
            assertEquals(expected, result.candidates.single().contextCost)
        }
    }

    @Test fun `maps every supported confidence wire value`() {
        val cases = listOf(
            "high" to DreamConfidence.HIGH,
            "medium" to DreamConfidence.MEDIUM,
            "low" to DreamConfidence.LOW,
        )

        cases.forEach { (wireValue, expected) ->
            val result = DreamOutputValidator.validate(jsonWithCandidate(candidateJson(
                confidence = wireValue,
                proposedAction = "reportOnly",
            )))

            assertEquals("Expected no errors for $wireValue", emptyList<String>(), result.errors)
            assertEquals(expected, result.candidates.single().confidence)
        }
    }

    @Test fun `maps every supported proposed action wire value`() {
        val cases = listOf(
            "applyLowRisk" to DreamProposedAction.APPLY_LOW_RISK,
            "proposeDiff" to DreamProposedAction.PROPOSE_DIFF,
            "reportOnly" to DreamProposedAction.REPORT_ONLY,
        )

        cases.forEach { (wireValue, expected) ->
            val result = DreamOutputValidator.validate(jsonWithCandidate(candidateJson(
                proposedAction = wireValue,
            )))

            assertEquals("Expected no errors for $wireValue", emptyList<String>(), result.errors)
            assertEquals(expected, result.candidates.single().proposedAction)
        }
    }

    @Test fun `rejects candidate without evidence`() {
        val result = DreamOutputValidator.validate("""
            {
              "candidates": [
                {
                  "kind": "linkNormalization",
                  "title": "Normalize rollout link",
                  "targetFiles": [".claude/wiki/index.md"],
                  "evidence": [],
                  "usefulness": "Keeps wiki references parseable.",
                  "contextCost": "neutral",
                  "confidence": "high",
                  "proposedAction": "applyLowRisk",
                  "patchPlan": "Replace one old wikilink."
                }
              ]
            }
        """.trimIndent())

        assertEquals(emptyList<DreamCandidate>(), result.candidates)
        assertTrue(result.errors.any { it.contains("evidence") })
    }

    @Test fun `rejects malformed JSON`() {
        val result = DreamOutputValidator.validate("""{"candidates": [""")

        assertEquals(emptyList<DreamCandidate>(), result.candidates)
        assertTrue(result.errors.any { it.contains("Malformed JSON") })
    }

    @Test fun `rejects unsupported enum wire values`() {
        val cases = listOf(
            candidateJson(kind = "unknownKind", proposedAction = "reportOnly") to "unsupported kind",
            candidateJson(evidence = evidenceJson(type = "unknownEvidence"), proposedAction = "reportOnly") to "unsupported evidence type",
            candidateJson(contextCost = "unknown-context", proposedAction = "reportOnly") to "unsupported contextCost",
            candidateJson(confidence = "unknown", proposedAction = "reportOnly") to "unsupported confidence",
            candidateJson(proposedAction = "unknownAction") to "unsupported proposedAction",
        )

        cases.forEach { (candidate, expectedError) ->
            val result = DreamOutputValidator.validate(jsonWithCandidate(candidate))

            assertEquals("Expected candidate to be rejected for $expectedError", emptyList<DreamCandidate>(), result.candidates)
            assertTrue("Expected error containing $expectedError", result.errors.any { it.contains(expectedError) })
        }
    }

    @Test fun `rejects missing or blank required scalar fields`() {
        val cases = listOf(
            candidateJson(title = "") to "title",
            candidateJson(usefulness = "   ") to "usefulness",
            candidateJson(patchPlan = "") to "patchPlan",
            candidateJsonMissingTitle() to "title",
        )

        cases.forEach { (candidate, expectedError) ->
            val result = DreamOutputValidator.validate(jsonWithCandidate(candidate))

            assertEquals("Expected candidate to be rejected for $expectedError", emptyList<DreamCandidate>(), result.candidates)
            assertTrue("Expected error containing $expectedError", result.errors.any { it.contains(expectedError) })
        }
    }

    @Test fun `rejects evidence array with no valid evidence entries`() {
        val result = DreamOutputValidator.validate(jsonWithCandidate(candidateJson(
            evidence = evidenceJson(type = "unknownEvidence", ref = "", summary = " "),
            proposedAction = "reportOnly",
        )))

        assertEquals(emptyList<DreamCandidate>(), result.candidates)
        assertTrue(result.errors.any { it.contains("evidence") })
    }

    @Test fun `mixed valid and invalid batch keeps valid candidates`() {
        val result = DreamOutputValidator.validate("""
            {
              "candidates": [
                ${candidateJson()},
                ${candidateJson(evidence = "[]")}
              ]
            }
        """.trimIndent())

        assertEquals(1, result.candidates.size)
        assertEquals("Normalize rollout link", result.candidates.single().title)
        assertTrue(result.errors.any { it.contains("evidence") })
    }

    @Test fun `absolute target file is rejected`() {
        val result = DreamOutputValidator.validate(jsonWithCandidate(candidateJson(
            targetFiles = """["/Users/me/project/.claude/wiki/index.md"]""",
        )))

        assertEquals(emptyList<DreamCandidate>(), result.candidates)
        assertTrue(result.errors.any { it.contains("targetFiles") })
    }

    @Test fun `target file outside wiki is rejected`() {
        val result = DreamOutputValidator.validate(jsonWithCandidate(candidateJson(
            targetFiles = """["src/main/kotlin/Foo.kt"]""",
        )))

        assertEquals(emptyList<DreamCandidate>(), result.candidates)
        assertTrue(result.errors.any { it.contains("targetFiles") })
    }

    @Test fun `missing concept with apply low risk is rejected`() {
        val result = DreamOutputValidator.validate(jsonWithCandidate(candidateJson(
            kind = "missingConcept",
            contextCost = "neutral",
            confidence = "high",
            proposedAction = "applyLowRisk",
        )))

        assertEquals(emptyList<DreamCandidate>(), result.candidates)
        assertTrue(result.errors.any { it.contains("applyLowRisk") })
    }

    @Test fun `index cleanup with apply low risk is rejected`() {
        val result = DreamOutputValidator.validate(jsonWithCandidate(candidateJson(
            kind = "indexCleanup",
            contextCost = "shrinks-context",
            confidence = "high",
            proposedAction = "applyLowRisk",
        )))

        assertEquals(emptyList<DreamCandidate>(), result.candidates)
        assertTrue(result.errors.any { it.contains("applyLowRisk") })
    }

    @Test fun `link normalization with multiple targets and apply low risk is rejected`() {
        val result = DreamOutputValidator.validate(jsonWithCandidate(candidateJson(
            targetFiles = """[".claude/wiki/index.md", ".claude/wiki/concepts/rollout.md"]""",
        )))

        assertEquals(emptyList<DreamCandidate>(), result.candidates)
        assertTrue(result.errors.any { it.contains("applyLowRisk") })
    }

    @Test fun `backslash target is rejected`() {
        val result = DreamOutputValidator.validate(jsonWithCandidate(candidateJson(
            targetFiles = """[".claude/wiki\\index.md"]""",
        )))

        assertEquals(emptyList<DreamCandidate>(), result.candidates)
        assertTrue(result.errors.any { it.contains("targetFiles") })
    }

    @Test fun `wiki directory target is rejected`() {
        val result = DreamOutputValidator.validate(jsonWithCandidate(candidateJson(
            targetFiles = """[".claude/wiki/"]""",
        )))

        assertEquals(emptyList<DreamCandidate>(), result.candidates)
        assertTrue(result.errors.any { it.contains("targetFiles") })
    }

    @Test fun `non markdown target is rejected`() {
        val result = DreamOutputValidator.validate(jsonWithCandidate(candidateJson(
            targetFiles = """[".claude/wiki/index.txt"]""",
        )))

        assertEquals(emptyList<DreamCandidate>(), result.candidates)
        assertTrue(result.errors.any { it.contains("targetFiles") })
    }

    @Test fun `unknown semantic field is rejected`() {
        val result = DreamOutputValidator.validate(jsonWithCandidate(candidateJson(
            extraFields = ""","deleteFiles":[".claude/wiki/index.md"]""",
        )))

        assertEquals(emptyList<DreamCandidate>(), result.candidates)
        assertTrue(result.errors.any { it.contains("deleteFiles") })
    }

    @Test fun `unknown root semantic field is rejected`() {
        val result = DreamOutputValidator.validate("""
            {
              "candidates": [
                ${candidateJson()}
              ],
              "applyNow": true
            }
        """.trimIndent())

        assertEquals(emptyList<DreamCandidate>(), result.candidates)
        assertTrue(result.errors.any { it.contains("applyNow") })
    }

    private companion object {
        val VALID_JSON = """
            {
              "candidates": [
                {
                  "kind": "linkNormalization",
                  "title": "Normalize rollout link",
                  "targetFiles": [".claude/wiki/index.md"],
                  "evidence": [
                    {
                      "type": "staleLink",
                      "ref": ".claude/wiki/index.md",
                      "summary": "old wikilink"
                    }
                  ],
                  "usefulness": "Keeps wiki references parseable.",
                  "contextCost": "neutral",
                  "confidence": "high",
                  "proposedAction": "applyLowRisk",
                  "patchPlan": "Replace one old wikilink."
                }
              ]
            }
        """.trimIndent()

        fun jsonWithCandidate(candidate: String): String = """
            {
              "candidates": [
                $candidate
              ]
            }
        """.trimIndent()

        fun candidateJson(
            kind: String = "linkNormalization",
            title: String = "Normalize rollout link",
            targetFiles: String = """[".claude/wiki/index.md"]""",
            evidence: String = evidenceJson(),
            usefulness: String = "Keeps wiki references parseable.",
            contextCost: String = "neutral",
            confidence: String = "high",
            proposedAction: String = "applyLowRisk",
            patchPlan: String = "Replace one old wikilink.",
            extraFields: String = "",
        ): String = """
            {
              "kind": "$kind",
              "title": "$title",
              "targetFiles": $targetFiles,
              "evidence": $evidence,
              "usefulness": "$usefulness",
              "contextCost": "$contextCost",
              "confidence": "$confidence",
              "proposedAction": "$proposedAction",
              "patchPlan": "$patchPlan"$extraFields
            }
        """.trimIndent()

        fun candidateJsonMissingTitle(): String = """
            {
              "kind": "linkNormalization",
              "targetFiles": [".claude/wiki/index.md"],
              "evidence": ${evidenceJson()},
              "usefulness": "Keeps wiki references parseable.",
              "contextCost": "neutral",
              "confidence": "high",
              "proposedAction": "applyLowRisk",
              "patchPlan": "Replace one old wikilink."
            }
        """.trimIndent()

        fun evidenceJson(
            type: String = "staleLink",
            ref: String = ".claude/wiki/index.md",
            summary: String = "old wikilink",
        ): String = """
            [
              {
                "type": "$type",
                "ref": "$ref",
                "summary": "$summary"
              }
            ]
        """.trimIndent()
    }
}
