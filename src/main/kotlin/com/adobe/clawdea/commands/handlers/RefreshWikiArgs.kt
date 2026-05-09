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

data class RefreshWikiArgs(
    val forceDream: Boolean = false,
    val statusOnly: Boolean = false,
    val applyLowRisk: Boolean = false,
) {
    companion object {
        fun parse(raw: String): RefreshWikiArgs {
            val flags = raw.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.toSet()
            return RefreshWikiArgs(
                forceDream = "--dream" in flags,
                statusOnly = "--status" in flags,
                applyLowRisk = "--apply-low-risk" in flags,
            )
        }
    }
}

data class RefreshWikiStatus(
    val lastRunAt: String,
    val lastSuccessfulScanAt: String,
    val lastStatus: String,
    val filteredCandidateCount: Int,
    val pendingEventTypes: List<String>,
    val dreamGateDue: Boolean,
    val dreamGateReasons: List<String>,
    val observedSignalUnits: Int,
    val processedSignalUnits: Int,
    val minSignalUnits: Int,
)

object RefreshWikiStatusFormatter {
    fun format(status: RefreshWikiStatus): String {
        val pendingSummary = if (status.pendingEventTypes.isEmpty()) {
            "none"
        } else {
            status.pendingEventTypes
                .groupingBy { it }
                .eachCount()
                .entries
                .sortedBy { it.key }
                .joinToString(", ") { "${it.key}=${it.value}" }
        }
        val gateSummary = if (status.dreamGateDue) {
            "due"
        } else {
            "not due (${status.dreamGateReasons.ifEmpty { listOf("unknown") }.joinToString(",")})"
        }
        return "Dream wiki status: last run ${valueOrNever(status.lastRunAt)}; " +
            "last successful scan ${valueOrNever(status.lastSuccessfulScanAt)}; " +
            "last status ${status.lastStatus.ifBlank { "none" }}; " +
            "filtered candidates ${status.filteredCandidateCount}; " +
            "pending drift ${status.pendingEventTypes.size} ($pendingSummary); " +
            "gate $gateSummary; " +
            "signal ${status.observedSignalUnits - status.processedSignalUnits}/${status.minSignalUnits}."
    }

    private fun valueOrNever(value: String): String = value.ifBlank { "never" }
}
