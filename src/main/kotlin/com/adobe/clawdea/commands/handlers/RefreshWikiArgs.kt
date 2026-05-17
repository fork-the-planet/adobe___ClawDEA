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
    val statusOnly: Boolean = false,
    val applyLowRisk: Boolean = false,
) {
    companion object {
        fun parse(raw: String): RefreshWikiArgs {
            val flags = raw.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.toSet()
            return RefreshWikiArgs(
                statusOnly = "--status" in flags,
                applyLowRisk = "--apply-low-risk" in flags,
            )
        }
    }
}

data class RefreshWikiStatus(
    val lastRunAt: String,
    val pendingEventTypes: List<String>,
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
        return "Wiki drift status: last run ${valueOrNever(status.lastRunAt)}; " +
            "pending drift ${status.pendingEventTypes.size} ($pendingSummary)."
    }

    private fun valueOrNever(value: String): String = value.ifBlank { "never" }
}
