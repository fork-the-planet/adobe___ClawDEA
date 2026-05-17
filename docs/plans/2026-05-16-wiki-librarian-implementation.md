# Wiki Librarian Subagent Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the main agent's brittle keyword `search_wiki` with delegation to a `wiki-librarian` Claude Code subagent that reads the wiki in its own fresh LLM context per call, returns synthesised answers, and deposits gap-suggestions into the existing drift-state queue for review at wiki refresh time.

**Architecture:** Use Claude Code's native subagent mechanism (`.claude/agents/*.md` + `Task` tool) as the framework substrate — no custom sub-agent runtime. The librarian's tool allowlist is gated via the agent file's frontmatter; its only write path is a new `record_wiki_suggestion` MCP tool that appends to a new `suggestions` array in `.claude/wiki/.drift-state.json`. Suggestions flow through the existing `DriftDetectionService` / `DriftAutoApplier` pipeline alongside the existing `DriftEvent` variants. `WikiSuggestion` is never auto-applied in v1 — it always surfaces for user review.

**Tech Stack:** Kotlin (JVM 21), IntelliJ Platform 2026.1, Gson (for `.drift-state.json` round-trip), JUnit 4. No new dependencies.

**Spec:** `docs/specs/2026-05-16-wiki-librarian-subagent-design.md`.

---

## File Map

**Create:**

- `src/main/resources/agents/wiki-librarian.md` — canonical agent definition shipped in plugin jar
- `.claude/agents/wiki-librarian.md` — ClawDEA's own dogfood copy (identical content; committed)
- `src/main/kotlin/com/adobe/clawdea/knowledge/wiki/WikiSuggestionWriter.kt`
- `src/main/kotlin/com/adobe/clawdea/knowledge/wiki/WikiLibrarianInstaller.kt`
- `src/test/kotlin/com/adobe/clawdea/knowledge/wiki/WikiSuggestionWriterTest.kt`
- `src/test/kotlin/com/adobe/clawdea/knowledge/wiki/WikiLibrarianInstallerTest.kt`

**Modify:**

- `src/main/kotlin/com/adobe/clawdea/settings/ClawDEASettings.kt:80` — add `enableWikiLibrarian: Boolean = true` near `enableKnowledgeLayer`
- `src/main/kotlin/com/adobe/clawdea/knowledge/drift/DriftEvent.kt:97` — add `WikiSuggestion` variant + `SuggestionKind` enum
- `src/main/kotlin/com/adobe/clawdea/knowledge/drift/DriftState.kt:32-52` — add `suggestions: List<WikiSuggestionRecord>` field
- `src/main/kotlin/com/adobe/clawdea/knowledge/drift/DriftAutoApplier.kt:32-49` — add `WikiSuggestion` branch (always returns false)
- `src/main/kotlin/com/adobe/clawdea/knowledge/drift/DriftDetectionService.kt:101-106` — `dismiss` strips from `state.suggestions`
- `src/main/kotlin/com/adobe/clawdea/knowledge/drift/DriftDetectionService.kt:154-187` — `collectRaw` injects `state.suggestions` as events
- `src/main/kotlin/com/adobe/clawdea/knowledge/primer/sources/WikiIndexSource.kt:24-91` — `buildDirective()` selects librarian vs legacy text by setting
- `src/main/kotlin/com/adobe/clawdea/knowledge/primer/PrimerService.kt:47-99` — invoke `WikiLibrarianInstaller.ensureInstalled` before sources
- `src/main/kotlin/com/adobe/clawdea/mcp/McpWikiTools.kt:26-47` — gate `search_wiki` registration; add `record_wiki_suggestion`
- `src/main/kotlin/com/adobe/clawdea/chat/ChatPanel.kt:1041-1090` — extend `/seed-wiki` closure to call installer
- `src/test/kotlin/com/adobe/clawdea/knowledge/drift/DriftStateStoreTest.kt` — extend
- `src/test/kotlin/com/adobe/clawdea/knowledge/drift/DriftAutoApplierTest.kt` — extend
- `src/test/kotlin/com/adobe/clawdea/knowledge/primer/sources/WikiIndexSourceTest.kt` — restructure (legacy directive tests stay; new ones added)
- `src/test/kotlin/com/adobe/clawdea/mcp/McpWikiToolsTest.kt` — restructure
- `docs/user-guide.md` — knowledge layer section: librarian + opt-out + "delete to reinstall"

---

## Task 1: Add `enableWikiLibrarian` setting

**Files:**
- Modify: `src/main/kotlin/com/adobe/clawdea/settings/ClawDEASettings.kt:71-80`

This is the foundational toggle. Default `true` means the librarian is on for everyone; the legacy `search_wiki` + legacy directive return only when explicitly disabled. No new test file — the field's behaviour is exercised by Tasks 9 and 10 (which read this setting).

- [ ] **Step 1: Read the file** to confirm the exact insertion line.

```bash
sed -n '70,82p' src/main/kotlin/com/adobe/clawdea/settings/ClawDEASettings.kt
```

Expected: line 72 is `var enableKnowledgeLayer: Boolean = true,` followed by `repoStateWarnThresholdMs` comment block at line 73.

- [ ] **Step 2: Add the new field** immediately after `enableKnowledgeLayer`.

Use `str-replace-editor` to insert after line 72:

```kotlin
        // Knowledge layer (Phase 1: REPO_STATE + primer)
        var enableKnowledgeLayer: Boolean = true,
        /**
         * When true (default), the primer directive teaches the main agent to delegate
         * project-design questions to the `wiki-librarian` Claude Code subagent via
         * Task, and `search_wiki` is not registered as an MCP tool. When false, the
         * legacy directive ("first call must be a wiki probe via search_wiki") is
         * emitted and `search_wiki` is registered. See
         * docs/specs/2026-05-16-wiki-librarian-subagent-design.md.
         */
        var enableWikiLibrarian: Boolean = true,
```

- [ ] **Step 3: Compile** to verify no syntax errors.

```bash
./gradlew compileKotlin
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Run the full test suite** to confirm no regression from adding the field.

```bash
./gradlew test
```

Expected: `BUILD SUCCESSFUL`. The `ClawDEASettings` data class gets a new field with a default, so all existing call sites compile unchanged.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/adobe/clawdea/settings/ClawDEASettings.kt
git commit -m "feat: add enableWikiLibrarian setting (default true)"
```


## Task 2: Add `WikiSuggestion` variant + `SuggestionKind` enum to `DriftEvent`

**Files:**
- Modify: `src/main/kotlin/com/adobe/clawdea/knowledge/drift/DriftEvent.kt:21-97`
- Modify: `src/main/kotlin/com/adobe/clawdea/knowledge/drift/DriftAutoApplier.kt:32-49` (close the `when`)
- Test: `src/test/kotlin/com/adobe/clawdea/knowledge/drift/DriftEventTest.kt` (new file)

Adds the new sealed-class variant **and** closes the resulting non-exhaustive `when` in `DriftAutoApplier.apply` in the same commit so the build is never red between tasks. Signatures are `wiki-suggestion:<kind>:<primaryTarget>`, where `primaryTarget` is the first `targetFiles` entry that doesn't end in `index.md` (since every new concept page touches the index → not a useful dedup key). Fields are all simple types (no `Path`, no `Instant`) so Gson round-trips for free in Task 3.

- [ ] **Step 1: Write the failing test.** Create `src/test/kotlin/com/adobe/clawdea/knowledge/drift/DriftEventTest.kt`:

```kotlin
/*
 * Copyright 2026 Adobe. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
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
```

- [ ] **Step 2: Run the test to verify it fails.**

```bash
./gradlew test --tests "com.adobe.clawdea.knowledge.drift.DriftEventTest"
```

Expected: compilation failure — `Unresolved reference: WikiSuggestion`, `Unresolved reference: SuggestionKind`.

- [ ] **Step 3: Implement the new variant + enum AND close the `when` in `DriftAutoApplier`.** Two file edits in one step.

First, edit `src/main/kotlin/com/adobe/clawdea/knowledge/drift/DriftEvent.kt`. Insert the new variant immediately before the closing `}` of `sealed class DriftEvent` (current line 97), and add the enum at file scope after that closing brace:

```kotlin
    data class WikiSuggestion(
        val kind: SuggestionKind,
        val title: String,
        val rationale: String,
        val targetFiles: List<String>,
        val sourcePage: String?,
        val recordedAt: String,
    ) : DriftEvent() {
        override val signature: String =
            "wiki-suggestion:${kind.name}:${primaryTarget(targetFiles)}"

        companion object {
            internal fun primaryTarget(targetFiles: List<String>): String {
                val nonIndex = targetFiles.firstOrNull { !it.endsWith("index.md") }
                return nonIndex ?: targetFiles.firstOrNull() ?: ""
            }
        }
    }
}

enum class SuggestionKind { missingConcept, staleConcept, incompleteConcept }
```

Second, edit `src/main/kotlin/com/adobe/clawdea/knowledge/drift/DriftAutoApplier.kt:32-49`. Add `is DriftEvent.WikiSuggestion,` to the existing list of never-auto-applied variants so the `when` stays exhaustive:

```kotlin
    fun apply(events: List<DriftEvent>, today: String = todayIso()): List<DriftEvent> {
        val applied = mutableListOf<DriftEvent>()
        for (event in events) {
            val ok = when (event) {
                is DriftEvent.CodeRename -> applyCodeRename(event)
                is DriftEvent.ManifestStale -> applyManifestStale(event, today)
                is DriftEvent.DreamLinkNormalization -> applyDreamLinkNormalization(event)
                is DriftEvent.DreamIndexCleanup,
                is DriftEvent.DreamSourceReferenceFix,
                is DriftEvent.DreamDuplicateConcept,
                is DriftEvent.DreamStaleConcept,
                is DriftEvent.DreamMissingConcept,
                is DriftEvent.WikiSuggestion,
                -> false
            }
            if (ok) applied += event
        }
        return applied
    }
```

This keeps the build green at every commit. Field types: `targetFiles` and `sourcePage` are `String` (not `Path`) and `recordedAt` is `String` (not `Instant`) so the default Gson serialiser used by `DriftStateStore` round-trips them without custom type adapters.

- [ ] **Step 4: Run tests to verify they pass.**

```bash
./gradlew test --tests "com.adobe.clawdea.knowledge.drift.*"
```

Expected: all drift tests pass (4 new + existing `DriftAutoApplierTest` / `DriftStateStoreTest` / detector tests). Build is fully green.

- [ ] **Step 5: Commit.**

```bash
git add src/main/kotlin/com/adobe/clawdea/knowledge/drift/DriftEvent.kt \
        src/main/kotlin/com/adobe/clawdea/knowledge/drift/DriftAutoApplier.kt \
        src/test/kotlin/com/adobe/clawdea/knowledge/drift/DriftEventTest.kt
git commit -m "feat: add WikiSuggestion variant + close DriftAutoApplier when"
```


## Task 3: Add `suggestions` field to `DriftState`

**Files:**
- Modify: `src/main/kotlin/com/adobe/clawdea/knowledge/drift/DriftState.kt:32-52`
- Test: `src/test/kotlin/com/adobe/clawdea/knowledge/drift/DriftStateStoreTest.kt` (extend)

`DriftState` is a Gson-round-tripped data class. Adding `suggestions: List<DriftEvent.WikiSuggestion>` is the persistent home for librarian-recorded suggestions. Gson handles this directly because `DriftEvent.WikiSuggestion` is a final data class with simple types (no polymorphism needed). The `signature` field is serialised; on read-back, Gson sets fields by reflection so the value comes from JSON rather than being recomputed by the initializer. For our usage (always write via constructor + read back), this is correct.

- [ ] **Step 1: Write the failing tests.** Extend `src/test/kotlin/com/adobe/clawdea/knowledge/drift/DriftStateStoreTest.kt` by appending:

```kotlin
    @Test fun `read returns empty suggestions when file is missing`() {
        val tmp = Files.createTempDirectory("drift")
        val state = DriftStateStore.read(claudeDir = tmp)
        assertEquals(emptyList<DriftEvent.WikiSuggestion>(), state.suggestions)
    }

    @Test fun `read returns empty suggestions when v1 file has no suggestions field`() {
        val tmp = Files.createTempDirectory("drift")
        val wikiDir = tmp.resolve("wiki")
        Files.createDirectories(wikiDir)
        Files.writeString(
            wikiDir.resolve(".drift-state.json"),
            """{"lastScanAt":"2026-05-01T00:00:00Z","dismissed":["sig-a"]}""",
        )
        val state = DriftStateStore.read(claudeDir = tmp)
        assertEquals(listOf("sig-a"), state.dismissed)
        assertEquals(emptyList<DriftEvent.WikiSuggestion>(), state.suggestions)
    }

    @Test fun `write then read round-trips suggestions list`() {
        val tmp = Files.createTempDirectory("drift")
        val suggestion = DriftEvent.WikiSuggestion(
            kind = SuggestionKind.missingConcept,
            title = "Add concept for FilesystemRefreshCoordinator",
            rationale = "Referenced from multiple subsystems; no page exists.",
            targetFiles = listOf(
                ".claude/wiki/concepts/filesystem-refresh-coordinator.md",
                ".claude/wiki/index.md",
            ),
            sourcePage = null,
            recordedAt = "2026-05-16T16:30:00Z",
        )
        DriftStateStore.write(
            claudeDir = tmp,
            state = DriftState(suggestions = listOf(suggestion)),
        )
        val state = DriftStateStore.read(claudeDir = tmp)
        assertEquals(1, state.suggestions.size)
        val read = state.suggestions[0]
        assertEquals(SuggestionKind.missingConcept, read.kind)
        assertEquals(suggestion.title, read.title)
        assertEquals(suggestion.targetFiles, read.targetFiles)
        assertEquals(suggestion.signature, read.signature)
    }
```

- [ ] **Step 2: Run tests to verify they fail.**

```bash
./gradlew test --tests "com.adobe.clawdea.knowledge.drift.DriftStateStoreTest"
```

Expected: compilation failure — `Unresolved reference: suggestions` in `state.suggestions` and `DriftState(suggestions = ...)`.

- [ ] **Step 3: Add the field.** Edit `src/main/kotlin/com/adobe/clawdea/knowledge/drift/DriftState.kt`. Insert the new field at the end of the constructor parameter list (after `dreamLockAcquiredAt`, before the closing `)`):

```kotlin
data class DriftState(
    val lastScanAt: String = "",
    val dismissed: List<String> = emptyList(),
    val probeMisses: List<ProbeMiss> = emptyList(),
    val userCorrections: List<UserCorrectionRecord> = emptyList(),
    val dreamLastRunAt: String = "",
    val dreamLastSuccessfulScanAt: String = "",
    val dreamLastFailedScanAt: String = "",
    val dreamLastDueCheckAt: String = "",
    val dreamLastStatus: String = "",
    val dreamProcessedSignalUnits: Int = 0,
    val dreamObservedSignalUnits: Int = 0,
    val dreamFilteredCandidateCount: Int = 0,
    val dreamLockOwner: String = "",
    val dreamLockAcquiredAt: String = "",
    val suggestions: List<DriftEvent.WikiSuggestion> = emptyList(),
) {
```

The `MAX_PROBE_MISSES` / `MAX_USER_CORRECTIONS` constants in the companion object stay unchanged.

- [ ] **Step 4: Run tests to verify they pass.**

```bash
./gradlew test --tests "com.adobe.clawdea.knowledge.drift.DriftStateStoreTest"
```

Expected: all tests passing (existing + 3 new).

- [ ] **Step 5: Commit.**

```bash
git add src/main/kotlin/com/adobe/clawdea/knowledge/drift/DriftState.kt \
        src/test/kotlin/com/adobe/clawdea/knowledge/drift/DriftStateStoreTest.kt
git commit -m "feat: persist WikiSuggestion list in DriftState"
```

---

## Task 4: Regression test for `WikiSuggestion` non-applicability

**Files:**
- Modify: `src/test/kotlin/com/adobe/clawdea/knowledge/drift/DriftAutoApplierTest.kt` (extend)

Task 2 already added the `is DriftEvent.WikiSuggestion` branch to keep the build green. This task adds a *behavioural* regression test so the spec's "never auto-applied in v1" promise is asserted in code, not just in commit messages. Departure from strict TDD is acknowledged: the test passes immediately, since the implementation arrived in Task 2 for compile-safety reasons.

- [ ] **Step 1: Write the regression test.** Append to `src/test/kotlin/com/adobe/clawdea/knowledge/drift/DriftAutoApplierTest.kt`:

```kotlin
    @Test fun `apply never auto-applies WikiSuggestion`() {
        val event = DriftEvent.WikiSuggestion(
            kind = SuggestionKind.missingConcept,
            title = "Add concept",
            rationale = "Real subsystem with no coverage.",
            targetFiles = listOf(".claude/wiki/concepts/foo.md"),
            sourcePage = null,
            recordedAt = "2026-05-16T16:30:00Z",
        )
        val applied = DriftAutoApplier.apply(events = listOf(event))
        assertTrue(applied.isEmpty())
    }
```

Import `assertTrue` from `org.junit.Assert` if not already imported.

- [ ] **Step 2: Run the test — it should pass immediately.**

```bash
./gradlew test --tests "com.adobe.clawdea.knowledge.drift.DriftAutoApplierTest"
```

Expected: PASS, including the new case. (Task 2's `DriftAutoApplier.apply` change is what makes this pass.)

- [ ] **Step 3: Verify the test would have caught a regression.** Optional sanity check — temporarily remove `is DriftEvent.WikiSuggestion,` from the `false` branch in `DriftAutoApplier.apply` and confirm the test fails to compile. Then put the line back. Skip this step if you trust the source-control diff.

- [ ] **Step 4: Run the full drift test package.**

```bash
./gradlew test --tests "com.adobe.clawdea.knowledge.drift.*"
```

Expected: all drift tests pass.

- [ ] **Step 5: Commit.**

```bash
git add src/test/kotlin/com/adobe/clawdea/knowledge/drift/DriftAutoApplierTest.kt
git commit -m "test: assert WikiSuggestion is never auto-applied"
```


## Task 5: Create `WikiSuggestionWriter`

**Files:**
- Create: `src/main/kotlin/com/adobe/clawdea/knowledge/wiki/WikiSuggestionWriter.kt`
- Test: `src/test/kotlin/com/adobe/clawdea/knowledge/wiki/WikiSuggestionWriterTest.kt`

Encapsulates the load-validate-dedup-persist dance for one suggestion. Keeps the MCP handler (Task 6) thin. Returns a sealed `Result` so the handler can produce the right MCP response without re-checking error conditions.

- [ ] **Step 1: Write the failing tests.** Create the new test file:

```kotlin
/*
 * Copyright 2026 Adobe. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 */
package com.adobe.clawdea.knowledge.wiki

import com.adobe.clawdea.knowledge.drift.DriftState
import com.adobe.clawdea.knowledge.drift.DriftStateStore
import com.adobe.clawdea.knowledge.drift.SuggestionKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.time.Instant

class WikiSuggestionWriterTest {

    private fun newClaudeDir() = Files.createTempDirectory("clawdea-wiki-sugg")

    @Test fun `records new suggestion and persists to drift-state`() {
        val claudeDir = newClaudeDir()
        val writer = WikiSuggestionWriter(claudeDir)
        val result = writer.record(
            kind = "missingConcept",
            title = "Add FilesystemRefreshCoordinator page",
            rationale = "Referenced from multiple subsystems with no coverage.",
            targetFilesCsv = ".claude/wiki/concepts/fsrc.md, .claude/wiki/index.md",
            sourcePage = null,
            recordedAt = Instant.parse("2026-05-16T16:30:00Z"),
        )
        assertTrue(result is WikiSuggestionWriter.Result.Recorded)
        val recorded = result as WikiSuggestionWriter.Result.Recorded
        assertTrue(recorded.isNew)
        assertEquals("wiki-suggestion:missingConcept:.claude/wiki/concepts/fsrc.md", recorded.signature)

        val state = DriftStateStore.read(claudeDir)
        assertEquals(1, state.suggestions.size)
        assertEquals(listOf(".claude/wiki/concepts/fsrc.md", ".claude/wiki/index.md"), state.suggestions[0].targetFiles)
    }

    @Test fun `re-recording same signature updates in place`() {
        val claudeDir = newClaudeDir()
        val writer = WikiSuggestionWriter(claudeDir)
        writer.record("missingConcept", "Old title here", "Old rationale of the gap.",
            ".claude/wiki/concepts/x.md", null, Instant.parse("2026-05-16T10:00:00Z"))
        val second = writer.record("missingConcept", "New title here", "Updated rationale of the gap.",
            ".claude/wiki/concepts/x.md", null, Instant.parse("2026-05-16T18:00:00Z"))
        assertTrue(second is WikiSuggestionWriter.Result.Recorded)
        assertEquals(false, (second as WikiSuggestionWriter.Result.Recorded).isNew)

        val state = DriftStateStore.read(claudeDir)
        assertEquals(1, state.suggestions.size)
        assertEquals("New title here", state.suggestions[0].title)
        assertEquals("2026-05-16T18:00:00Z", state.suggestions[0].recordedAt)
    }

    @Test fun `dismissed signature is not re-persisted`() {
        val claudeDir = newClaudeDir()
        DriftStateStore.write(claudeDir, DriftState(
            dismissed = listOf("wiki-suggestion:staleConcept:.claude/wiki/concepts/y.md"),
        ))
        val writer = WikiSuggestionWriter(claudeDir)
        val result = writer.record("staleConcept", "Concept Y is wrong",
            "Wiki says X but source has Y now.",
            ".claude/wiki/concepts/y.md", ".claude/wiki/concepts/y.md")
        assertTrue(result is WikiSuggestionWriter.Result.Dismissed)
        val state = DriftStateStore.read(claudeDir)
        assertEquals(0, state.suggestions.size)
    }

    @Test fun `invalid kind is rejected`() {
        val writer = WikiSuggestionWriter(newClaudeDir())
        val result = writer.record("notARealKind", "Some title here",
            "Some rationale of the gap.", ".claude/wiki/concepts/a.md", null)
        assertTrue(result is WikiSuggestionWriter.Result.Invalid)
    }

    @Test fun `title too short is rejected`() {
        val writer = WikiSuggestionWriter(newClaudeDir())
        val result = writer.record("missingConcept", "ab",
            "Some rationale of the gap.", ".claude/wiki/concepts/a.md", null)
        assertTrue(result is WikiSuggestionWriter.Result.Invalid)
    }

    @Test fun `rationale too short is rejected`() {
        val writer = WikiSuggestionWriter(newClaudeDir())
        val result = writer.record("missingConcept", "Valid title here",
            "short", ".claude/wiki/concepts/a.md", null)
        assertTrue(result is WikiSuggestionWriter.Result.Invalid)
    }

    @Test fun `target path outside wiki is rejected`() {
        val writer = WikiSuggestionWriter(newClaudeDir())
        val result = writer.record("missingConcept", "Valid title here",
            "Some rationale of the gap.", "src/main/kotlin/Foo.kt", null)
        assertTrue(result is WikiSuggestionWriter.Result.Invalid)
    }

    @Test fun `target path with parent traversal is rejected`() {
        val writer = WikiSuggestionWriter(newClaudeDir())
        val result = writer.record("missingConcept", "Valid title here",
            "Some rationale of the gap.",
            ".claude/wiki/../../../etc/passwd.md", null)
        assertTrue(result is WikiSuggestionWriter.Result.Invalid)
    }

    @Test fun `non-md target path is rejected`() {
        val writer = WikiSuggestionWriter(newClaudeDir())
        val result = writer.record("missingConcept", "Valid title here",
            "Some rationale of the gap.", ".claude/wiki/concepts/foo.txt", null)
        assertTrue(result is WikiSuggestionWriter.Result.Invalid)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails.**

```bash
./gradlew test --tests "com.adobe.clawdea.knowledge.wiki.WikiSuggestionWriterTest"
```

Expected: compilation failure — `Unresolved reference: WikiSuggestionWriter`.

- [ ] **Step 3: Implement `WikiSuggestionWriter`.** Create `src/main/kotlin/com/adobe/clawdea/knowledge/wiki/WikiSuggestionWriter.kt`:

```kotlin
/*
 * Copyright 2026 Adobe. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 */
package com.adobe.clawdea.knowledge.wiki

import com.adobe.clawdea.knowledge.drift.DriftEvent
import com.adobe.clawdea.knowledge.drift.DriftStateStore
import com.adobe.clawdea.knowledge.drift.SuggestionKind
import java.nio.file.Path
import java.time.Instant

/**
 * Validates a librarian-recorded wiki suggestion and persists it into
 * `.claude/wiki/.drift-state.json`'s `suggestions` array. Idempotent on
 * signature: re-recording the same gap updates the existing entry's
 * title/rationale/recordedAt rather than appending a duplicate.
 */
class WikiSuggestionWriter(private val claudeDir: Path) {

    sealed class Result {
        data class Recorded(val signature: String, val isNew: Boolean) : Result()
        data class Dismissed(val signature: String) : Result()
        data class Invalid(val reason: String) : Result()
    }

    fun record(
        kind: String,
        title: String,
        rationale: String,
        targetFilesCsv: String,
        sourcePage: String?,
        recordedAt: Instant = Instant.now(),
    ): Result {
        val kindEnum = parseKind(kind)
            ?: return Result.Invalid("kind must be missingConcept, staleConcept, or incompleteConcept (got '$kind')")
        if (title.length !in 3..120)
            return Result.Invalid("title length must be 3..120 (got ${title.length})")
        if (rationale.length !in 10..800)
            return Result.Invalid("rationale length must be 10..800 (got ${rationale.length})")

        val targetFiles = targetFilesCsv.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (targetFiles.isEmpty())
            return Result.Invalid("target_files must list at least one wiki path")
        targetFiles.firstOrNull { !isSafeWikiPath(it) }?.let { bad ->
            return Result.Invalid("target_files entry is not a safe wiki path: $bad")
        }
        val safeSourcePage = sourcePage?.trim()?.takeIf { it.isNotEmpty() }
        if (safeSourcePage != null && !isSafeWikiPath(safeSourcePage)) {
            return Result.Invalid("source_page is not a safe wiki path: $safeSourcePage")
        }

        val event = DriftEvent.WikiSuggestion(
            kind = kindEnum,
            title = title,
            rationale = rationale,
            targetFiles = targetFiles,
            sourcePage = safeSourcePage,
            recordedAt = recordedAt.toString(),
        )

        val state = DriftStateStore.read(claudeDir)
        if (event.signature in state.dismissed) return Result.Dismissed(event.signature)

        val existing = state.suggestions.indexOfFirst { it.signature == event.signature }
        val newSuggestions = if (existing >= 0) {
            state.suggestions.toMutableList().also { it[existing] = event }
        } else {
            state.suggestions + event
        }
        DriftStateStore.write(claudeDir, state.copy(suggestions = newSuggestions))
        return Result.Recorded(event.signature, isNew = existing < 0)
    }

    private fun parseKind(raw: String): SuggestionKind? = try {
        SuggestionKind.valueOf(raw)
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun isSafeWikiPath(path: String): Boolean {
        if (path.isBlank()) return false
        if (!path.startsWith(".claude/wiki/")) return false
        if (!path.endsWith(".md")) return false
        val segments = path.split('/')
        return segments.none { it == ".." || it.isBlank() || it.contains('\\') }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass.**

```bash
./gradlew test --tests "com.adobe.clawdea.knowledge.wiki.WikiSuggestionWriterTest"
```

Expected: 9 tests passing.

- [ ] **Step 5: Commit.**

```bash
git add src/main/kotlin/com/adobe/clawdea/knowledge/wiki/WikiSuggestionWriter.kt \
        src/test/kotlin/com/adobe/clawdea/knowledge/wiki/WikiSuggestionWriterTest.kt
git commit -m "feat: WikiSuggestionWriter validates + persists librarian suggestions"
```


## Task 6: `McpWikiTools` surgery — gate `search_wiki`, add `record_wiki_suggestion`

**Files:**
- Modify: `src/main/kotlin/com/adobe/clawdea/mcp/McpWikiTools.kt:26-47, 73-110, 116-124`
- Modify: `src/test/kotlin/com/adobe/clawdea/mcp/McpWikiToolsTest.kt`

Three coupled changes in `McpWikiTools.registerAll`: (1) `read_wiki_page` registration unchanged, (2) `search_wiki` registration moves behind `if (!enableWikiLibrarian)`, (3) `record_wiki_suggestion` registered always (no caller when librarian is off, harmless). Existing `searchWiki`, `parsePathTokens`, `maybeRecordProbeMiss` methods stay — used only when search_wiki is registered. New `recordWikiSuggestion` handler delegates to `WikiSuggestionWriter`.

- [ ] **Step 1: Write the failing tests.** Replace `src/test/kotlin/com/adobe/clawdea/mcp/McpWikiToolsTest.kt` contents:

```kotlin
/*
 * Copyright 2026 Adobe. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 */
package com.adobe.clawdea.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class McpWikiToolsTest {

    @Test fun `tool name constants are stable`() {
        assertEquals("read_wiki_page", McpWikiTools.READ_TOOL_NAME)
        assertEquals("search_wiki", McpWikiTools.SEARCH_TOOL_NAME)
        assertEquals("record_wiki_suggestion", McpWikiTools.RECORD_SUGGESTION_TOOL_NAME)
    }

    @Test fun `read description mentions concept and wiki`() {
        assertTrue(McpWikiTools.READ_TOOL_DESCRIPTION.contains("wiki", ignoreCase = true))
        assertTrue(McpWikiTools.READ_TOOL_DESCRIPTION.contains("concept", ignoreCase = true))
    }

    @Test fun `search description mentions search and wiki`() {
        assertTrue(McpWikiTools.SEARCH_TOOL_DESCRIPTION.contains("search", ignoreCase = true))
        assertTrue(McpWikiTools.SEARCH_TOOL_DESCRIPTION.contains("wiki", ignoreCase = true))
    }

    @Test fun `record-suggestion description names the three kinds`() {
        val desc = McpWikiTools.RECORD_SUGGESTION_TOOL_DESCRIPTION
        assertTrue(desc.contains("missingConcept"))
        assertTrue(desc.contains("staleConcept"))
        assertTrue(desc.contains("incompleteConcept"))
        assertTrue(desc.contains("wiki-librarian"))
    }
}
```

A handler-level integration test would need a real `Project`, which is heavy. The `WikiSuggestionWriterTest` (Task 5) already exercises every code path the handler delegates to. The registration assertions are sufficient at this layer.

- [ ] **Step 2: Run the test to verify it fails.**

```bash
./gradlew test --tests "com.adobe.clawdea.mcp.McpWikiToolsTest"
```

Expected: compilation failure — `Unresolved reference: RECORD_SUGGESTION_TOOL_NAME` and `RECORD_SUGGESTION_TOOL_DESCRIPTION`.

- [ ] **Step 3: Update `McpWikiTools.kt`.** Replace the file's `registerAll` method and companion-object constants. The full updated file:

```kotlin
/*
 * Copyright 2026 Adobe. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 */
package com.adobe.clawdea.mcp

import com.adobe.clawdea.knowledge.drift.DriftDetectionService
import com.adobe.clawdea.knowledge.wiki.WikiPageReader
import com.adobe.clawdea.knowledge.wiki.WikiPath
import com.adobe.clawdea.knowledge.wiki.WikiSearcher
import com.adobe.clawdea.knowledge.wiki.WikiSuggestionWriter
import com.adobe.clawdea.settings.ClawDEASettings
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.intellij.openapi.project.Project
import java.nio.file.Paths

class McpWikiTools(private val project: Project) {

    fun registerAll(router: McpToolRouter) {
        val state = ClawDEASettings.getInstance().state
        router.register(
            name = READ_TOOL_NAME,
            description = READ_TOOL_DESCRIPTION,
            properties = listOf(
                Triple("name", "string", "Page name without .md (e.g. 'rollout-flow' or 'index')"),
                Triple("kind", "string", "Optional: 'concept' (default), 'source', or 'index'"),
            ),
            required = listOf("name"),
            handler = ::readWikiPage,
        )
        router.register(
            name = RECORD_SUGGESTION_TOOL_NAME,
            description = RECORD_SUGGESTION_TOOL_DESCRIPTION,
            properties = listOf(
                Triple("kind", "string", "One of missingConcept, staleConcept, incompleteConcept"),
                Triple("title", "string", "3-7 word title for the proposed change"),
                Triple("rationale", "string", "1-2 sentence explanation of what was observed"),
                Triple("target_files", "string", "Comma-separated wiki paths the change would touch (each under .claude/wiki/, ending in .md)"),
                Triple("source_page", "string", "Optional: wiki page consulted when the gap was noticed"),
            ),
            required = listOf("kind", "title", "rationale", "target_files"),
            handler = ::recordWikiSuggestion,
        )
        if (!state.enableWikiLibrarian) {
            router.register(
                name = SEARCH_TOOL_NAME,
                description = SEARCH_TOOL_DESCRIPTION,
                properties = listOf(
                    Triple("query", "string", "Case-insensitive substring to search for in wiki pages"),
                    Triple("pathTokens", "array:string", "Optional path tokens from diff context (e.g. policies, clientlibs, jcr_root) to match against page titles and headings"),
                ),
                required = listOf("query"),
                handler = ::searchWiki,
            )
        }
    }

    private fun wikiPath(): WikiPath? {
        val basePath = project.basePath ?: return null
        val state = ClawDEASettings.getInstance().state
        return WikiPath(Paths.get(basePath, state.claudeDirName, state.wikiSubdir))
    }

    private fun readWikiPage(args: Map<String, String>): McpToolRouter.ToolResult {
        val name = args["name"] ?: return McpToolRouter.ToolResult("Missing 'name' argument", isError = true)
        val kind = (args["kind"] ?: "concept").lowercase()
        val wp = wikiPath() ?: return McpToolRouter.ToolResult("No project basePath", isError = true)
        val reader = WikiPageReader(wp)
        val content = when (kind) {
            "concept" -> reader.readConcept(name)
            "source" -> reader.readSource(name)
            "index" -> reader.readIndex()
            else -> return McpToolRouter.ToolResult("Unknown kind '$kind' (expected concept|source|index)", isError = true)
        }
        return if (content == null) {
            McpToolRouter.ToolResult("(no $kind page named '$name')")
        } else {
            McpToolRouter.ToolResult(content)
        }
    }

    private fun recordWikiSuggestion(args: Map<String, String>): McpToolRouter.ToolResult {
        val basePath = project.basePath
            ?: return McpToolRouter.ToolResult("No project basePath", isError = true)
        val state = ClawDEASettings.getInstance().state
        val claudeDir = Paths.get(basePath, state.claudeDirName)
        val writer = WikiSuggestionWriter(claudeDir)
        val kind = args["kind"] ?: return McpToolRouter.ToolResult("Missing 'kind' argument", isError = true)
        val title = args["title"] ?: return McpToolRouter.ToolResult("Missing 'title' argument", isError = true)
        val rationale = args["rationale"] ?: return McpToolRouter.ToolResult("Missing 'rationale' argument", isError = true)
        val targetFiles = args["target_files"] ?: return McpToolRouter.ToolResult("Missing 'target_files' argument", isError = true)
        val result = writer.record(
            kind = kind,
            title = title,
            rationale = rationale,
            targetFilesCsv = targetFiles,
            sourcePage = args["source_page"],
        )
        return when (result) {
            is WikiSuggestionWriter.Result.Recorded ->
                McpToolRouter.ToolResult("""{"status":"recorded","signature":"${result.signature}","isNew":${result.isNew}}""")
            is WikiSuggestionWriter.Result.Dismissed ->
                McpToolRouter.ToolResult("""{"status":"dismissed","signature":"${result.signature}"}""")
            is WikiSuggestionWriter.Result.Invalid ->
                McpToolRouter.ToolResult(result.reason, isError = true)
        }
    }

    private fun searchWiki(args: Map<String, String>): McpToolRouter.ToolResult {
        val query = args["query"] ?: return McpToolRouter.ToolResult("Missing 'query' argument", isError = true)
        val pathTokens = parsePathTokens(args["pathTokens"])
        val wp = wikiPath() ?: return McpToolRouter.ToolResult("No project basePath", isError = true)
        val hits = WikiSearcher(wp).search(query, pathTokens)

        maybeRecordProbeMiss(query, pathTokens, hits.size, args["taskContext"])

        if (hits.isEmpty()) return McpToolRouter.ToolResult("(no matches for '$query')")
        val sb = StringBuilder()
        for (hit in hits.take(20)) {
            sb.appendLine("--- ${hit.relativePath}:${hit.firstHitLine} (${hit.matchCount} match${if (hit.matchCount > 1) "es" else ""}) ---")
            sb.appendLine(hit.snippet)
            sb.appendLine()
        }
        return McpToolRouter.ToolResult(sb.toString().trimEnd())
    }

    private fun parsePathTokens(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            GSON.fromJson<List<String>>(raw, STRING_LIST_TYPE)?.filter { it.isNotBlank() } ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun maybeRecordProbeMiss(query: String, pathTokens: List<String>, hitCount: Int, taskContext: String?) {
        val queryTokenCount = query.split("\\s+".toRegex()).size
        val isNonTrivial = queryTokenCount >= 2 || query.length >= 8
        if (!isNonTrivial) return
        if (hitCount >= 2) return
        val contextHash = taskContext?.hashCode()?.toUInt()?.toString(16)
            ?: project.basePath?.hashCode()?.toUInt()?.toString(16)
            ?: "unknown"
        project.getService(DriftDetectionService::class.java)
            .recordProbeMiss(query, pathTokens, hitCount, contextHash)
    }

    companion object {
        private val GSON = Gson()
        private val STRING_LIST_TYPE = object : TypeToken<List<String>>() {}.type

        const val READ_TOOL_NAME = "read_wiki_page"
        const val READ_TOOL_DESCRIPTION =
            "Read a wiki page (concept, source, or index) from .claude/wiki/. Use to access " +
            "synthesized project knowledge that complements CLAUDE.md."
        const val SEARCH_TOOL_NAME = "search_wiki"
        const val SEARCH_TOOL_DESCRIPTION =
            "Search the project wiki at .claude/wiki/ for a substring query. Returns ranked " +
            "snippets with file path and line number; use read_wiki_page for full content."
        const val RECORD_SUGGESTION_TOOL_NAME = "record_wiki_suggestion"
        const val RECORD_SUGGESTION_TOOL_DESCRIPTION =
            "Record a proposed wiki improvement (missingConcept | staleConcept | " +
            "incompleteConcept) for the user to review at wiki refresh time. Use sparingly — " +
            "one per distinct gap. Not surfaced to the main chat; only the wiki-librarian " +
            "subagent's allowlist contains this tool. Returns a short ack with the recorded " +
            "signature."
    }
}
```

- [ ] **Step 4: Run tests to verify they pass.**

```bash
./gradlew test --tests "com.adobe.clawdea.mcp.McpWikiToolsTest"
./gradlew compileKotlin
```

Expected: 4 tests passing; full compile clean.

- [ ] **Step 5: Commit.**

```bash
git add src/main/kotlin/com/adobe/clawdea/mcp/McpWikiTools.kt \
        src/test/kotlin/com/adobe/clawdea/mcp/McpWikiToolsTest.kt
git commit -m "feat: register record_wiki_suggestion; gate search_wiki behind enableWikiLibrarian"
```


## Task 7: Wire `WikiSuggestion` into `DriftDetectionService`

**Files:**
- Modify: `src/main/kotlin/com/adobe/clawdea/knowledge/drift/DriftDetectionService.kt:101-106, 154-187`
- Modify: `src/test/kotlin/com/adobe/clawdea/knowledge/drift/DriftDetectionServiceTest.kt` (extend or new)

Two coupled edits in the same file:

1. **`dismiss(signature)`** — also strips matching entries from `state.suggestions` so the entry doesn't get reloaded on every subsequent `rescan` only to be silently filtered out (which would work, but the file would grow).
2. **`collectRaw(...)`** — inject `beforeState.suggestions` into the raw event stream, so they flow through `filterDismissed` → `applyAndDismiss` → listeners exactly like any other `DriftEvent`.

- [ ] **Step 1: Check whether `DriftDetectionServiceTest.kt` exists.**

```bash
test -f src/test/kotlin/com/adobe/clawdea/knowledge/drift/DriftDetectionServiceTest.kt && echo EXISTS || echo MISSING
```

The directory listing in plan setup confirmed it exists. Open it to see its existing pattern (test naming, fixture setup) before appending. If it imports `Project`, the new tests should follow that pattern; if it's pure-Kotlin (testing the companion-object `collectRaw` directly), the new test can do likewise.

- [ ] **Step 2: Write the failing tests.** Append to `src/test/kotlin/com/adobe/clawdea/knowledge/drift/DriftDetectionServiceTest.kt`:

```kotlin
    @Test fun `collectRaw includes state suggestions in raw events`() {
        val tmp = Files.createTempDirectory("drift-svc")
        val claudeDir = tmp.resolve(".claude")
        Files.createDirectories(claudeDir.resolve("wiki"))
        val suggestion = DriftEvent.WikiSuggestion(
            kind = SuggestionKind.missingConcept,
            title = "Add concept for X",
            rationale = "Real subsystem with no coverage.",
            targetFiles = listOf(".claude/wiki/concepts/x.md"),
            sourcePage = null,
            recordedAt = "2026-05-16T16:30:00Z",
        )
        val beforeState = DriftState(suggestions = listOf(suggestion))
        val settingsState = ClawDEASettings.State()

        val result = DriftDetectionService.collectRaw(
            projectRoot = tmp,
            claudeDir = claudeDir,
            beforeState = beforeState,
            settingsState = settingsState,
            now = Instant.parse("2026-05-16T17:00:00Z"),
            runDreamScan = false,
        )
        assertTrue(result.events.any { it is DriftEvent.WikiSuggestion && it.signature == suggestion.signature })
    }
```

If the existing test file is in a different package or uses different imports, mirror that pattern. Required imports: `java.nio.file.Files`, `java.time.Instant`, `com.adobe.clawdea.settings.ClawDEASettings`, plus the drift package's own types.

A second test for the dismiss-strips-suggestions behaviour needs a real `Project`, which `DriftDetectionService` requires for `dismiss(...)`. If the existing test file already uses a mock or fixture for `Project`, follow it. Otherwise add a pure unit test on `DriftStateStore` round-tripping the strip behaviour:

```kotlin
    @Test fun `dismiss strips matching suggestion from persisted state`() {
        val tmp = Files.createTempDirectory("drift-dismiss")
        val sig = "wiki-suggestion:missingConcept:.claude/wiki/concepts/x.md"
        val suggestion = DriftEvent.WikiSuggestion(
            kind = SuggestionKind.missingConcept,
            title = "title here",
            rationale = "rationale text here.",
            targetFiles = listOf(".claude/wiki/concepts/x.md"),
            sourcePage = null,
            recordedAt = "2026-05-16T16:30:00Z",
        )
        assertEquals(sig, suggestion.signature)
        DriftStateStore.write(tmp, DriftState(suggestions = listOf(suggestion)))

        // Simulate what the new dismiss() body will do.
        DriftStateStore.update(tmp) { state ->
            state.copy(
                dismissed = state.dismissed + sig,
                suggestions = state.suggestions.filterNot { it.signature == sig },
            )
        }
        val read = DriftStateStore.read(tmp)
        assertTrue(sig in read.dismissed)
        assertTrue(read.suggestions.isEmpty())
    }
```

(This test will pass with no code changes — it tests the documented behaviour of the future `dismiss`. After Step 3, the production code matches.)

- [ ] **Step 3: Implement the two changes.**

First change — `dismiss` (line 101–106). Replace:

```kotlin
    fun dismiss(signature: String) {
        val basePath = project.basePath ?: return
        val claudeDir = Paths.get(basePath).resolve(".claude")
        DriftStateStore.update(claudeDir) { it.copy(dismissed = it.dismissed + signature) }
        rescan()
    }
```

with:

```kotlin
    fun dismiss(signature: String) {
        val basePath = project.basePath ?: return
        val claudeDir = Paths.get(basePath).resolve(".claude")
        DriftStateStore.update(claudeDir) { state ->
            state.copy(
                dismissed = state.dismissed + signature,
                suggestions = state.suggestions.filterNot { it.signature == signature },
            )
        }
        rescan()
    }
```

Second change — `collectRaw` (line 154–187). Locate the line `val out = mutableListOf<DriftEvent>()` (currently line 155) and immediately after the existing `out += CodeRenameDetector.detect(...)` and `out += ManifestStaleDetector.detect(...)` blocks (but before the `val dreamState = ...` block), insert:

```kotlin
            out += beforeState.suggestions
```

The final shape of the body becomes:

```kotlin
            val out = mutableListOf<DriftEvent>()
            val wikiDir = claudeDir.resolve("wiki")
            out += CodeRenameDetector.detect(
                wikiDir = wikiDir,
                sourceRoots = listOf(
                    projectRoot.resolve("src/main/kotlin"),
                    projectRoot.resolve("src/main/java"),
                ),
            )
            val manifestPath = WorkspaceDiscovery.discover(projectRoot)
            if (manifestPath != null) {
                out += ManifestStaleDetector.detect(manifestPath)
            }
            out += beforeState.suggestions

            // The remaining body (val dreamState = ..., dreamResult = ...,
            // out += dreamResult.events, RawCollection(...) return) stays
            // exactly as it is at current lines 169-186. Do not edit those
            // lines — only the single `out += beforeState.suggestions` is new.
```

- [ ] **Step 4: Run tests to verify they pass.**

```bash
./gradlew test --tests "com.adobe.clawdea.knowledge.drift.*"
```

Expected: all drift tests pass (existing + the new `collectRaw includes state suggestions` and `dismiss strips matching suggestion`).

- [ ] **Step 5: Commit.**

```bash
git add src/main/kotlin/com/adobe/clawdea/knowledge/drift/DriftDetectionService.kt \
        src/test/kotlin/com/adobe/clawdea/knowledge/drift/DriftDetectionServiceTest.kt
git commit -m "feat: WikiSuggestion flows through drift pipeline; dismiss strips from queue"
```


## Task 8: Rewrite `WikiIndexSource.buildDirective` — librarian default, legacy fallback

**Files:**
- Modify: `src/main/kotlin/com/adobe/clawdea/knowledge/primer/sources/WikiIndexSource.kt:24-91`
- Modify: `src/test/kotlin/com/adobe/clawdea/knowledge/primer/sources/WikiIndexSourceTest.kt`

Split into two internal builders:

- `buildLibrarianDirective()` (no args) — new text teaching Task delegation. Used when `enableWikiLibrarian = true`.
- `buildLegacyDirective(wikiDir, autoUpdate)` — verbatim current text. Used when `enableWikiLibrarian = false`.

`load(project)` picks one based on the setting. Tests target the two builders directly; the original test cases get renamed (assertions unchanged) and new cases cover the librarian text.

- [ ] **Step 1: Write the failing tests.** Replace `src/test/kotlin/com/adobe/clawdea/knowledge/primer/sources/WikiIndexSourceTest.kt`:

```kotlin
/*
 * Copyright 2026 Adobe. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 */
package com.adobe.clawdea.knowledge.primer.sources

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WikiIndexSourceTest {

    // --- Librarian directive (default, enableWikiLibrarian=true) ---

    @Test fun `librarian directive teaches Task delegation`() {
        val directive = WikiIndexSource.buildLibrarianDirective()
        assertTrue(directive.contains("wiki-librarian"))
        assertTrue(directive.contains("Task(subagent_type=\"wiki-librarian\""))
        assertTrue(directive.contains("Hard rule"))
    }

    @Test fun `librarian directive preserves read_wiki_page escape hatch`() {
        val directive = WikiIndexSource.buildLibrarianDirective()
        assertTrue(directive.contains("read_wiki_page"))
    }

    @Test fun `librarian directive does not mention search_wiki`() {
        val directive = WikiIndexSource.buildLibrarianDirective()
        assertFalse("search_wiki should not appear in librarian directive",
            directive.contains("search_wiki"))
    }

    @Test fun `librarian directive carves out the two exceptions`() {
        val directive = WikiIndexSource.buildLibrarianDirective()
        assertTrue(directive.contains("already have a wiki page slug"))
        assertTrue(directive.contains("Purely lexical edits"))
    }

    // --- Legacy directive (enableWikiLibrarian=false) ---

    @Test fun `legacy directive asks for standard markdown wiki links`() {
        val directive = WikiIndexSource.buildLegacyDirective(".claude/wiki", autoUpdate = false)
        assertTrue(directive.contains("Use standard Markdown links between wiki pages:"))
        assertTrue(directive.contains("[Concept](concept.md)"))
        assertTrue(directive.contains("[Concept](concepts/concept.md)"))
        assertTrue(directive.contains("Do not create new `[[concept]]` references"))
    }

    @Test fun `legacy auto-update gap action asks for markdown index links`() {
        val directive = WikiIndexSource.buildLegacyDirective(".claude/wiki", autoUpdate = true)
        assertTrue(directive.contains("[Title](concepts/<slug>.md)"))
    }

    @Test fun `legacy reviewed gap action preserves propose tools`() {
        val directive = WikiIndexSource.buildLegacyDirective(".claude/wiki", autoUpdate = false)
        assertTrue(directive.contains("`propose_write`"))
        assertTrue(directive.contains("`propose_edit`"))
        assertTrue(directive.contains("[Title](concepts/<slug>.md)"))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail.**

```bash
./gradlew test --tests "com.adobe.clawdea.knowledge.primer.sources.WikiIndexSourceTest"
```

Expected: compilation failure — `Unresolved reference: buildLibrarianDirective` and `Unresolved reference: buildLegacyDirective`.

- [ ] **Step 3: Rewrite `WikiIndexSource.kt`.** The complete updated file:

```kotlin
/*
 * Copyright 2026 Adobe. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 */
package com.adobe.clawdea.knowledge.primer.sources

import com.adobe.clawdea.knowledge.primer.PrimerSource
import com.adobe.clawdea.knowledge.wiki.WikiPageReader
import com.adobe.clawdea.knowledge.wiki.WikiPath
import com.adobe.clawdea.settings.ClawDEASettings
import com.intellij.openapi.project.Project
import java.nio.file.Paths

class WikiIndexSource : PrimerSource {
    override val id = "wikiIndex"

    override fun load(project: Project): String? {
        val basePath = project.basePath ?: return null
        val state = ClawDEASettings.getInstance().state
        val wikiDir = Paths.get(basePath, state.claudeDirName, state.wikiSubdir)
        val reader = WikiPageReader(WikiPath(wikiDir))
        val index = reader.readIndex() ?: return null
        val directive = if (state.enableWikiLibrarian) {
            buildLibrarianDirective()
        } else {
            buildLegacyDirective(wikiDir.toString(), state.autoUpdateWiki)
        }
        return directive + "\n\n" + index
    }

    companion object {
        // History: sessions 9b36ff6b (#139), 537c8342 (#141), 1afd97af (#24),
        // and 2d41a87f (#86) all skipped the wiki under successively softer
        // wording. The hard-rule pattern (first call must be a wiki action,
        // exact tool named, alternatives explicitly listed) is what finally
        // worked. The v2 librarian directive evolves from the same lineage —
        // the first call moves from `search_wiki` to `Task(wiki-librarian)`,
        // but the hard-rule shape is preserved.

        internal fun buildLibrarianDirective(): String =
            """
                |## How this project's wiki works
                |
                |This project has a **wiki-librarian subagent** that holds the project's
                |design knowledge in its own fresh context every call. You ask it
                |questions; it answers from `.claude/wiki/`, verifies against current
                |source where it matters, and returns a synthesised answer with page
                |citations.
                |
                |**Hard rule: for any non-trivial question about how this project works** —
                |"where is X", "how does Y work", "what is the contract of Z", "why does
                |this do A instead of B" — your FIRST tool call must be:
                |
                |    Task(subagent_type="wiki-librarian", prompt="<the user's question, verbatim>")
                |
                |Not `Read`, not `search_text`, not `find_symbol`, not `Bash`. Exactly one
                |`Task` invocation. The librarian will name the files and entry points
                |to open; then the other tools are unrestricted.
                |
                |Two narrow exceptions:
                |
                |1. **You already have a wiki page slug.** If a previous turn or the
                |   librarian itself named `concepts/<slug>.md`, you can re-read it
                |   directly via `read_wiki_page(name='<slug>', kind='concept')` without
                |   a Task round-trip. The librarian is for *finding* and *synthesising*;
                |   direct read is for known pages.
                |
                |2. **Purely lexical edits.** Renames, formatting, lint where you already
                |   know the exact symbol or string. No design question = no librarian
                |   call.
                |
                |Below is the wiki index — use it to scope your question to the librarian
                |("how does the primer's wiki directive get built?" beats "wiki"). The
                |index is titles only; the actual knowledge is on the concept pages,
                |which only the librarian reads in a fresh context every time.
            """.trimMargin()

        internal fun buildLegacyDirective(wikiDir: String, autoUpdate: Boolean): String {
            val gapAction = if (autoUpdate) {
                "**write a new concept page** at `$wikiDir/concepts/<slug>.md` directly with the " +
                    "`Write` tool (auto-update is enabled — silent learning). Then append a " +
                    "matching bullet to `$wikiDir/index.md` with a standard Markdown link like " +
                    "`[Title](concepts/<slug>.md)`."
            } else {
                "**draft a new concept page** at `$wikiDir/concepts/<slug>.md` via " +
                    "`propose_write` so the user reviews the diff. Also extend `$wikiDir/index.md` " +
                    "with a matching bullet via `propose_edit` using a standard Markdown link like " +
                    "`[Title](concepts/<slug>.md)`."
            }
            return """
                |## How to use this wiki
                |
                |**Hard rule: your FIRST code-search tool call after reading the user message
                |must be a wiki probe.** Not `Read`, not `search_text`, not `find_files`, not
                |`Bash`. Exactly one `search_wiki` (1–3 keywords from the user's request) OR
                |one `read_wiki_page` (concept name from the index below). After that, the
                |other tools are unrestricted.
                |
                |`search_wiki` is for **orientation** — finding the right concept page that
                |names the files and entry points for a subsystem. `search_text` is for
                |**raw strings in code** — CLI flags, error messages, log lines. They are not
                |interchangeable; do the wiki probe regardless of how confident you feel
                |about a `search_text` plan.
                |
                |After the probe:
                |
                |1. **On hit:** read the page; it names the files, classes, and entry points
                |   to open. Use that to navigate instead of broad text search.
                |2. **On miss for a real subsystem** (multiple files, distinct responsibility),
                |   $gapAction Concept pages are ~150–250 lines: purpose, key files with
                |   line refs, control flow, gotchas. Skip page-creation only for one-file or
                |   purely lexical tasks (rename, format, lint).
                |
                |   Use standard Markdown links between wiki pages: from another concept page,
                |   `[Concept](concept.md)`; from the index, `[Concept](concepts/concept.md)`.
                |   Do not create new `[[concept]]` references.
                |3. Mention any wiki gaps you observed in your final reply so the user knows
                |   coverage improved (or where it still doesn't).
                |
                |The only tasks exempt from the probe are pure lexical edits (rename,
                |format, lint) where you already know the exact symbol or string to change.
            """.trimMargin()
        }
    }
}
```

Note: `buildLegacyDirective`'s body is **byte-for-byte identical** to the current `buildDirective(wikiDir, autoUpdate)` body — only the method name changes. This preserves the existing 4 legacy tests with no assertion edits.

- [ ] **Step 4: Run tests to verify they pass.**

```bash
./gradlew test --tests "com.adobe.clawdea.knowledge.primer.sources.WikiIndexSourceTest"
```

Expected: 7 tests passing (4 librarian + 3 legacy).

- [ ] **Step 5: Commit.**

```bash
git add src/main/kotlin/com/adobe/clawdea/knowledge/primer/sources/WikiIndexSource.kt \
        src/test/kotlin/com/adobe/clawdea/knowledge/primer/sources/WikiIndexSourceTest.kt
git commit -m "feat: split wiki directive into librarian (default) + legacy paths"
```


## Task 9: Create the canonical agent resource file

**Files:**
- Create: `src/main/resources/agents/wiki-librarian.md`

Plain resource file shipped in the plugin jar. No tests directly — Task 10's `WikiLibrarianInstaller` reads this resource and copies it into projects; that test validates round-trip integrity. The MCP tool prefix is `mcp__clawdea-intellij__*` (verified in `McpClientConfig.kt` and `McpProtocol.toolsListResponse`).

- [ ] **Step 1: Create the directory.**

```bash
mkdir -p src/main/resources/agents
```

- [ ] **Step 2: Write the agent file.** Create `src/main/resources/agents/wiki-librarian.md` with the exact content:

````markdown
---
name: wiki-librarian
description: |
  Answers questions about this project's design, subsystems, and conventions
  from .claude/wiki/. Use for any "how does X work" / "where is Y" /
  "what is the contract of Z" question about this codebase before doing
  your own code search. Returns a synthesised answer with page citations;
  may log a wiki suggestion for the user to review at refresh time.
tools:
  - Read
  - mcp__clawdea-intellij__read_wiki_page
  - mcp__clawdea-intellij__search_text
  - mcp__clawdea-intellij__find_files
  - mcp__clawdea-intellij__find_symbol
  - mcp__clawdea-intellij__find_usages
  - mcp__clawdea-intellij__find_callers
  - mcp__clawdea-intellij__resolve_symbol
  - mcp__clawdea-intellij__find_diagnostics
  - mcp__clawdea-intellij__record_wiki_suggestion
---

You are this project's **wiki librarian**. Your only job is to answer the
calling agent's question by consulting `.claude/wiki/` and, where it
matters, verifying against current source.

## Workflow

1. **Read the index first.** Your first tool call MUST be
   `read_wiki_page(name='index', kind='index')`. Do not skip this even if
   the question looks obvious — the index is your map.
2. **Pick relevant pages.** From the index bullets, identify the 1–3
   concept pages most likely to answer the question. Read them with
   `read_wiki_page(name='<slug>', kind='concept')`. Do not speculate from
   index titles — always open the page.
3. **Verify load-bearing claims.** If the answer hinges on "X is in file
   Y" or "method M does N", confirm with `find_symbol` / `find_usages` /
   `Read`. Never assert what you couldn't verify.
4. **Spot gaps.** If the question is about a real subsystem (multiple
   files, distinct responsibility) and no concept page covers it, OR you
   found a wiki claim contradicted by current source, call
   `record_wiki_suggestion` before answering. One suggestion per distinct
   gap.
5. **Answer** in 1–3 short paragraphs, citing pages by relative path
   (e.g. `.claude/wiki/concepts/primer.md`). If you logged a suggestion,
   mention it in one final line.

## Hard constraints

- No write tools. `record_wiki_suggestion` is your only write path.
- No `Bash`, `Edit`, `Write`, `propose_*`. They are not in your allowlist.
- Synthesise; do not repeat wiki pages verbatim.
- Only read pages the index says are relevant.
- If wiki and source both lack the answer, say so and name the closest
  pages. Don't fabricate.

## When to call `record_wiki_suggestion`

Three kinds, mirroring a subset of `DreamWikiDetector` candidate kinds:

- **missingConcept** — a real subsystem with no concept page covering it
  (multiple files, clear responsibility, the question revealed nothing
  to point at).
- **staleConcept** — a concept page contains a claim contradicted by
  current source (e.g. "lives in ClassX" but you found it in ClassY).
- **incompleteConcept** — a concept page exists but the question asked
  about an aspect it doesn't cover.

Provide:
- `kind` — one of the three above.
- `title` — 3–7 words, e.g. "Add concept page for FilesystemRefreshCoordinator".
- `rationale` — 1–2 sentences: what you observed + why a wiki change
  helps the *next* question of this shape.
- `target_files` — comma-separated paths under `.claude/wiki/` that
  would change. For a new page, list both
  `.claude/wiki/concepts/<slug>.md` and `.claude/wiki/index.md`.
- `source_page` — optional; the page you were reading when you noticed
  the gap.

## Output shape

Plain prose, no headings. Example:

> The primer is assembled by `PrimerService.refreshAndGet()`. It composes
> five sources in order: `ClaudeMdSource`, `WikiIndexSource`,
> `NotesSource`, `SiblingsSource`, `RepoStateSource`. The order is
> deliberate — see the comment in `PrimerService.kt` near line 33.
>
> Sources: `.claude/wiki/concepts/knowledge-layer.md`
> (Logged suggestion: missingConcept — Add primer-order rationale to knowledge-layer page)
````

- [ ] **Step 3: Verify the resource is on the classpath.** A `./gradlew assemble` build copies resources from `src/main/resources/` into the plugin jar automatically — no `build.gradle.kts` change required. Confirm with:

```bash
./gradlew processResources
find build/resources -name wiki-librarian.md
```

Expected: prints `build/resources/main/agents/wiki-librarian.md`.

- [ ] **Step 4: No production code references the file yet** — Task 10's installer reads it. Run the full test suite to confirm no regression:

```bash
./gradlew test
```

Expected: all tests pass.

- [ ] **Step 5: Commit.**

```bash
git add src/main/resources/agents/wiki-librarian.md
git commit -m "feat: add wiki-librarian Claude Code subagent definition"
```


## Task 10: Create `WikiLibrarianInstaller`

**Files:**
- Create: `src/main/kotlin/com/adobe/clawdea/knowledge/wiki/WikiLibrarianInstaller.kt`
- Test: `src/test/kotlin/com/adobe/clawdea/knowledge/wiki/WikiLibrarianInstallerTest.kt`

Reads `/agents/wiki-librarian.md` from the classpath and writes it to `<claudeDir>/agents/wiki-librarian.md` if missing. Atomic write (temp + rename) following the pattern in `DriftStateStore.write` and `DriftAutoApplier.atomicWrite`.

- [ ] **Step 1: Write the failing tests.**

```kotlin
/*
 * Copyright 2026 Adobe. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 */
package com.adobe.clawdea.knowledge.wiki

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class WikiLibrarianInstallerTest {

    @Test fun `ensureInstalled writes the resource when the file is missing`() {
        val claudeDir = Files.createTempDirectory("clawdea-install")
        val installer = WikiLibrarianInstaller()
        val result = installer.ensureInstalled(claudeDir)
        assertTrue(result is WikiLibrarianInstaller.InstallResult.Installed)
        val written = claudeDir.resolve("agents").resolve("wiki-librarian.md")
        assertTrue(Files.exists(written))
        val content = Files.readString(written)
        assertTrue(content.startsWith("---"))
        assertTrue(content.contains("name: wiki-librarian"))
        assertTrue(content.contains("mcp__clawdea-intellij__record_wiki_suggestion"))
    }

    @Test fun `ensureInstalled no-ops when file already present`() {
        val claudeDir = Files.createTempDirectory("clawdea-install")
        val agentsDir = claudeDir.resolve("agents")
        Files.createDirectories(agentsDir)
        Files.writeString(agentsDir.resolve("wiki-librarian.md"), "user-managed content")
        val installer = WikiLibrarianInstaller()
        val result = installer.ensureInstalled(claudeDir)
        assertTrue(result is WikiLibrarianInstaller.InstallResult.AlreadyPresent)
        assertEquals("user-managed content",
            Files.readString(agentsDir.resolve("wiki-librarian.md")))
    }

    @Test fun `ensureInstalled written content equals plugin resource`() {
        val claudeDir = Files.createTempDirectory("clawdea-install")
        WikiLibrarianInstaller().ensureInstalled(claudeDir)
        val written = Files.readString(claudeDir.resolve("agents").resolve("wiki-librarian.md"))
        val resource = WikiLibrarianInstaller::class.java
            .getResourceAsStream("/agents/wiki-librarian.md")!!
            .bufferedReader().use { it.readText() }
        assertEquals(resource, written)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail.**

```bash
./gradlew test --tests "com.adobe.clawdea.knowledge.wiki.WikiLibrarianInstallerTest"
```

Expected: compilation failure — `Unresolved reference: WikiLibrarianInstaller`.

- [ ] **Step 3: Implement.** Create `src/main/kotlin/com/adobe/clawdea/knowledge/wiki/WikiLibrarianInstaller.kt`:

```kotlin
/*
 * Copyright 2026 Adobe. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 */
package com.adobe.clawdea.knowledge.wiki

import com.intellij.openapi.diagnostic.Logger
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Ensures `<claudeDir>/agents/wiki-librarian.md` exists. Canonical text is
 * shipped as a plugin resource at `/agents/wiki-librarian.md`. Policy:
 * write-if-missing, never overwrite. To pick up an updated agent file from
 * a newer plugin version, the user deletes the file and runs ClawDEA;
 * the next refresh reinstalls.
 */
class WikiLibrarianInstaller {

    sealed class InstallResult {
        object AlreadyPresent : InstallResult()
        object Installed : InstallResult()
        data class Failed(val cause: Throwable) : InstallResult()
    }

    fun ensureInstalled(claudeDir: Path): InstallResult {
        val target = claudeDir.resolve(AGENTS_DIR).resolve(AGENT_FILE)
        if (Files.exists(target)) return InstallResult.AlreadyPresent

        return try {
            val resource = WikiLibrarianInstaller::class.java
                .getResourceAsStream("/$AGENTS_DIR/$AGENT_FILE")
                ?: return InstallResult.Failed(
                    IllegalStateException("Plugin resource not found: /$AGENTS_DIR/$AGENT_FILE")
                )
            val text = resource.bufferedReader().use { it.readText() }
            val parent = target.parent
            Files.createDirectories(parent)
            val temp = Files.createTempFile(parent, AGENT_FILE + ".tmp", "")
            try {
                Files.writeString(temp, text)
                try {
                    Files.move(temp, target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING)
                } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                if (Files.exists(temp)) {
                    try { Files.deleteIfExists(temp) } catch (_: Exception) {}
                }
            }
            InstallResult.Installed
        } catch (e: Throwable) {
            LOG.warn("WikiLibrarianInstaller failed for $target: ${e.message}")
            InstallResult.Failed(e)
        }
    }

    companion object {
        private val LOG = Logger.getInstance(WikiLibrarianInstaller::class.java)
        private const val AGENTS_DIR = "agents"
        private const val AGENT_FILE = "wiki-librarian.md"
    }
}
```

- [ ] **Step 4: Run tests to verify they pass.**

```bash
./gradlew test --tests "com.adobe.clawdea.knowledge.wiki.WikiLibrarianInstallerTest"
```

Expected: 3 tests passing.

- [ ] **Step 5: Commit.**

```bash
git add src/main/kotlin/com/adobe/clawdea/knowledge/wiki/WikiLibrarianInstaller.kt \
        src/test/kotlin/com/adobe/clawdea/knowledge/wiki/WikiLibrarianInstallerTest.kt
git commit -m "feat: WikiLibrarianInstaller copies agent definition into .claude/agents/"
```

---

## Task 11: Wire installer into `PrimerService` and `/seed-wiki`

**Files:**
- Modify: `src/main/kotlin/com/adobe/clawdea/knowledge/primer/PrimerService.kt:47-56`
- Modify: `src/main/kotlin/com/adobe/clawdea/chat/ChatPanel.kt:1041-1090`

Two call sites for `WikiLibrarianInstaller.ensureInstalled`. Both are idempotent (write-if-missing), so no harm in calling both. No new tests at this layer — `WikiLibrarianInstallerTest` (Task 10) already verifies the installer's behaviour, and the wiring is a single line that requires a live `Project` to test directly.

- [ ] **Step 1: Edit `PrimerService.refreshAndGet`.** Insert the installer call immediately after the `basePath` null-check (current line 51-55), before the `repoStateStart` line. The new section:

```kotlin
    fun refreshAndGet(): String {
        val settings = ClawDEASettings.getInstance().state
        if (!settings.enableKnowledgeLayer) return ""

        val basePath = project.basePath
        if (basePath == null) {
            log.warn("PrimerService: project has no basePath; skipping refresh")
            return cached.get()
        }

        if (settings.enableWikiLibrarian) {
            val claudeDir = Paths.get(basePath).resolve(settings.claudeDirName)
            when (val r = com.adobe.clawdea.knowledge.wiki.WikiLibrarianInstaller().ensureInstalled(claudeDir)) {
                is com.adobe.clawdea.knowledge.wiki.WikiLibrarianInstaller.InstallResult.Failed ->
                    log.warn("WikiLibrarianInstaller failed: ${r.cause.message}")
                else -> Unit
            }
        }

        val repoStateStart = System.currentTimeMillis()
        // ... rest unchanged
```

Failure mode: the installer logs at warn level and returns `Failed`; primer assembly continues. The librarian directive will still be emitted (Task 8), but invoking `Task(wiki-librarian, ...)` will error inside the CLI until the file is present. This is acceptable v1 behaviour — the warn log is the operational signal.

- [ ] **Step 2: Edit the `/seed-wiki` handler in `ChatPanel.kt`.** Find the closure registered at line 1041 (the `BridgeExpandingHandler` for `/seed-wiki`). At the start of the closure body, *before* the existing `val invariantTemplate = ...` line, insert:

```kotlin
            val basePath = project.basePath
            if (basePath != null) {
                val state = com.adobe.clawdea.settings.ClawDEASettings.getInstance().state
                if (state.enableWikiLibrarian) {
                    val claudeDir = java.nio.file.Paths.get(basePath).resolve(state.claudeDirName)
                    com.adobe.clawdea.knowledge.wiki.WikiLibrarianInstaller().ensureInstalled(claudeDir)
                }
            }
```

The handler body continues unchanged afterwards (loading prompt templates and returning the expanded prompt). This guarantees the agent file exists by the time `/seed-wiki`'s prompt is sent to the CLI.

- [ ] **Step 3: Build to confirm both call sites compile.**

```bash
./gradlew compileKotlin
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Run the full test suite.**

```bash
./gradlew test
```

Expected: all tests pass. No new tests at this layer — manual smoke test is documented in the plan's "Manual Verification" section after task 13.

- [ ] **Step 5: Commit.**

```bash
git add src/main/kotlin/com/adobe/clawdea/knowledge/primer/PrimerService.kt \
        src/main/kotlin/com/adobe/clawdea/chat/ChatPanel.kt
git commit -m "feat: auto-install wiki-librarian agent on primer refresh + /seed-wiki"
```


## Task 12: Commit ClawDEA's own dogfood copy

**Files:**
- Create: `.claude/agents/wiki-librarian.md` (ClawDEA repo root — committed, identical to plugin resource)

ClawDEA dogfoods the librarian on its own codebase. The committed copy mirrors `src/main/resources/agents/wiki-librarian.md`. When developers run ClawDEA on the ClawDEA repo, the installer's `ensureInstalled` no-ops (file already present from git checkout). Pull-request reviewers see agent-prompt changes by diffing both files.

- [ ] **Step 1: Copy the resource file to the dogfood location.**

```bash
mkdir -p .claude/agents
cp src/main/resources/agents/wiki-librarian.md .claude/agents/wiki-librarian.md
```

- [ ] **Step 2: Verify byte-for-byte equality.**

```bash
diff -q src/main/resources/agents/wiki-librarian.md .claude/agents/wiki-librarian.md
```

Expected: no output (files identical).

- [ ] **Step 3: Compile to confirm no impact.** The file isn't read by any code in the ClawDEA build itself — it's an artefact for the Claude Code CLI when run against this repo.

```bash
./gradlew compileKotlin
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Smoke check (optional, manual).** From a fresh `runIde` session opened against this repo, ask a wiki-coverable question in chat and observe whether `Task(wiki-librarian, ...)` is invoked. If you don't have a fresh sandbox handy, defer until after Task 13.

- [ ] **Step 5: Commit.**

```bash
git add .claude/agents/wiki-librarian.md
git commit -m "chore: dogfood wiki-librarian agent in ClawDEA repo"
```

---

## Task 13: Document the librarian in the user guide

**Files:**
- Modify: `docs/user-guide.md` (find the knowledge-layer section)

Documents the new default behaviour, the opt-out, and the "delete to refresh" mechanic. Skip if the user-guide is fully out of date or doesn't exist; in that case file a follow-up issue instead.

- [ ] **Step 1: Locate the knowledge-layer section.**

```bash
grep -n -i "knowledge layer\|wiki" docs/user-guide.md | head -20
```

Skim the existing structure to find the right anchor. The current guide likely has subsections like "Wiki", "Drift detection", or similar.

- [ ] **Step 2: Add a "Wiki librarian" subsection.** Insert (under the existing wiki section, or as a new subsection after it):

```markdown
### Wiki librarian

For any non-trivial question about this project's design, ClawDEA's main chat
delegates to a `wiki-librarian` Claude Code subagent rather than running a
keyword search itself. The librarian reads `.claude/wiki/` in its own fresh
LLM context every call, verifies claims against current source, and returns
a synthesised answer with citations. Wiki content never enters the main
chat's context, so it doesn't decay across long conversations.

The agent file lives at `.claude/agents/wiki-librarian.md` per project.
ClawDEA installs it automatically on first knowledge-layer use and on
`/seed-wiki`. To pick up an updated version that ships with a new plugin
release, delete the file and run ClawDEA — the installer re-writes it.
User-edited copies are preserved (installer never overwrites).

**When the librarian finds a wiki gap** while answering a question — a real
subsystem with no page, a stale claim contradicted by current source, or a
covered concept missing a relevant aspect — it logs a suggestion via the
`record_wiki_suggestion` MCP tool. Suggestions accumulate in
`.claude/wiki/.drift-state.json` and surface alongside other drift events:

- With `autoUpdateWiki` enabled, suggestions appear in the periodic
  Dream-scan notification cycle.
- Without `autoUpdateWiki`, suggestions wait until `/refresh-wiki` is
  invoked.

The librarian never writes wiki files directly. Authoring stays user-
initiated: review the suggestion, decide yes/no, and either dismiss it or
draft the wiki change through the main chat.

**Opt out** by setting `enableWikiLibrarian = false` in plugin settings.
This restores the legacy "search_wiki probe" directive and re-registers
`search_wiki` as an MCP tool. The librarian agent file remains on disk;
it's simply not invoked.
```

- [ ] **Step 3: Verify the file still renders cleanly.**

```bash
markdownlint docs/user-guide.md 2>&1 || true
```

If `markdownlint` isn't installed, just visually inspect the section structure.

- [ ] **Step 4: No tests to run.** Documentation-only change.

- [ ] **Step 5: Commit.**

```bash
git add docs/user-guide.md
git commit -m "docs: describe wiki librarian, suggestion queue, opt-out"
```

---

## Final verification

After all tasks land:

```bash
./gradlew compileKotlin
./gradlew test
./gradlew build -x buildSearchableOptions
```

All three should be `BUILD SUCCESSFUL`. The plugin zip ends up at `build/distributions/ClawDEA-<version>.zip`.

## Manual smoke test (post-merge, in a sandboxed IDE)

```bash
./gradlew runIde
```

1. Open a project with `.claude/wiki/` content (ClawDEA itself works).
2. Verify `.claude/agents/wiki-librarian.md` is present (auto-installed on first chat refresh, or committed for the ClawDEA repo).
3. In chat, ask "how does the primer get assembled?" and confirm the transcript shows a `Task(subagent_type="wiki-librarian", ...)` invocation followed by a synthesised answer with citations.
4. Ask a question about a subsystem with no concept page; check that `.claude/wiki/.drift-state.json` gains a `suggestions:` entry.
5. Run `/refresh-wiki` and confirm the suggestion shows in the drain output.
6. Flip `enableWikiLibrarian` to `false` in Settings; restart the sandboxed IDE; confirm the legacy directive appears in the primer and `search_wiki` is callable from the main chat.
7. Restore the setting; confirm librarian flow returns.
