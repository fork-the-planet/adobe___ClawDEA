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
package com.adobe.clawdea.commands.handlers

import org.junit.Assert.assertEquals
import org.junit.Test

class RefreshWikiArgsTest {

    @Test
    fun `parse returns defaults for blank input`() {
        assertEquals(RefreshWikiArgs(), RefreshWikiArgs.parse(""))
    }

    @Test
    fun `parse recognizes dream flag`() {
        assertEquals(RefreshWikiArgs(forceDream = true), RefreshWikiArgs.parse("--dream"))
    }

    @Test
    fun `parse recognizes status flag`() {
        assertEquals(RefreshWikiArgs(statusOnly = true), RefreshWikiArgs.parse("--status"))
    }

    @Test
    fun `parse recognizes apply low risk flag`() {
        assertEquals(RefreshWikiArgs(applyLowRisk = true), RefreshWikiArgs.parse("--apply-low-risk"))
    }

    @Test
    fun `parse recognizes combined flags in any order`() {
        assertEquals(
            RefreshWikiArgs(forceDream = true, statusOnly = true, applyLowRisk = true),
            RefreshWikiArgs.parse("  --apply-low-risk   --dream --status  "),
        )
    }

    @Test
    fun `status formatter includes pending event counts and gate reasons`() {
        val status = RefreshWikiStatus(
            lastRunAt = "2026-05-09T10:00:00Z",
            lastSuccessfulScanAt = "",
            lastStatus = "not-due:elapsed-time",
            filteredCandidateCount = 3,
            pendingEventTypes = listOf("CodeRename", "DreamMissingConcept", "CodeRename"),
            dreamGateDue = false,
            dreamGateReasons = listOf("elapsed-time", "insufficient-signal"),
            observedSignalUnits = 7,
            processedSignalUnits = 4,
            minSignalUnits = 5,
        )

        assertEquals(
            "Dream wiki status: last run 2026-05-09T10:00:00Z; " +
                "last successful scan never; " +
                "last status not-due:elapsed-time; " +
                "filtered candidates 3; " +
                "pending drift 3 (CodeRename=2, DreamMissingConcept=1); " +
                "gate not due (elapsed-time,insufficient-signal); " +
                "signal 3/5.",
            RefreshWikiStatusFormatter.format(status),
        )
    }
}
