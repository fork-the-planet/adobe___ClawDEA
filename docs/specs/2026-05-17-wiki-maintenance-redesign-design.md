# Wiki Maintenance Redesign — Design

| | |
|---|---|
| **Date** | 2026-05-17 |
| **Status** | Proposed |
| **Supersedes** | The Dream Wiki Maintenance pieces of `docs/superpowers/specs/2026-05-09-dream-wiki-maintenance-design.md`. The Wiki Librarian (`docs/specs/2026-05-16-wiki-librarian-subagent-design.md`) remains in effect — this design extends it with a complementary `wiki-author` subagent and replaces the dream-based detector with a git-driven one. |
| **Defers to later specs** | Real Anthropic Dreams integration (research preview, see "Future work") |

## Problem

Two failure modes in the current wiki maintenance pipeline:

1. **`DreamWikiDetector` doesn't fire.** It probes for a `/dream` slash command in `claude --help`, finds nothing (the command does not exist in shipped Claude Code as of 2026-05-17 — confirmed against `claude --help` and the public docs at `code.claude.com`), and returns `Dreams unavailable`. The "Claude Dreams" feature it was modelled on is in fact a Managed Agents REST API — not a CLI slash command — for curating Anthropic-hosted memory stores from session transcripts. The premise of `ClaudeDreamInvocation.kt` is simply wrong. Every drift scan in the user's experience has surfaced only `CodeRenameDetector` reference fixes; the dream-based detector has produced zero events.

2. **Drift events that do fire are routed wrong.** When the wiki-librarian queues a `WikiSuggestion` (or `CodeRenameDetector` flags a stale link), `/refresh-wiki` builds a prompt and forwards it to the *main* chat agent for handling. This burns main-chat context, requires the user to be in a session, and never silently applies — the user sees a diff dialog per page even when they have opted into `Auto-update wiki on drift`. The `autoUpdateWiki=true` setting today only governs the deterministic `CodeRename` link rewrites; it has no effect on the LLM-mediated event types because there is no auto-apply path that doesn't go through main chat. Worse, `/seed-wiki` instructs the agent to identify "5–10 concept areas" regardless of project complexity, capping the wiki at a size that doesn't scale with the codebase.

A third failure observed in the brainstorming session that produced this spec: the implementation diverged from `docs/specs/2026-05-16-wiki-librarian-subagent-design.md` (commits `baab575`, `841cb5a` switched the librarian install path from `WikiLibrarianInstaller` writing `.claude/agents/wiki-librarian.md` to a per-session `--agents` JSON injection) and *no detector caught the divergence*. The wiki and spec still describe a class that no longer exists. This is exactly the staleness pattern wiki maintenance is supposed to detect, and the current pipeline cannot detect it because:

- No detector reads recent commits.
- `DreamWikiDetector` never runs.
- `CodeRenameDetector` only catches broken Markdown links to renamed files; it does not catch "the wiki describes a class that was deleted but no link was used."

## Goals

- **Make recent git activity a first-class drift signal.** Every commit that lands on the local branch (whether authored by the user or pulled in from a remote) is a free, accurate, dated source of "what changed". A `CommitWikiDriftDetector` runs on every drift rescan and emits structured `CommitDrift` events identifying which wiki pages reference symbols/files touched by recent commits — handing the *judgment* of "is this stale?" to the wiki-author subagent.
- **Move wiki authoring out of the main chat.** A new `wiki-author` subagent is the only writer of wiki content during `/refresh-wiki`. The librarian remains read-only (writes only `record_wiki_suggestion`). The main chat is no longer involved in routine wiki maintenance.
- **Honour `Auto-update wiki on drift` end-to-end.** When on, the wiki-author writes pages directly and surfaces a one-line note in any active chat (matching the existing "no drift events detected" status message style). When off, the existing review popup pops up as today.
- **Drop the seed-wiki concept cap** when the librarian is on. Let `/seed-wiki` propose as many pages as the project warrants.
- **Drop the dream-based machinery.** Remove `ClaudeDreamInvocation`, `DreamWikiDetector`, `DreamDueGate`, `DreamWikiSettings`, the dream lock files, the `runDreamScanNow()` path, the `--force-dream` arg, the `dream*` settings on `ClawDEASettings.State`, and the `Dream*` `DriftEvent` variants. Their replacement is the commit-driven detector + wiki-author pair, which gives us deterministic detection and bounded LLM cost.
- **Document the future Dreams hook.** When Dreams research-preview goes public, ClawDEA can extract dream digests (curated memory store output) and feed them as additional input to the wiki-author. Spec'd as future work, not implemented in this PR.

## Non-goals

- Real Anthropic Dreams API integration. Deferred until research-preview is publicly available.
- Cross-project / cross-repo drift detection. The commit-driven detector reads only the current project's git log.
- LLM-judging *which* commits are wiki-relevant. The detector emits `CommitDrift` for every commit-touched page; the wiki-author judges relevance.
- Replacing `WikiSearcher` / removing the `enableWikiLibrarian=false` legacy path. That escape hatch remains untouched.

## Architecture

```
git ref change (commit / fetch / pull / branch switch)
  → GitRepositoryChangeListener fires
  → DriftDetectionService.rescan()
       CommitWikiDriftDetector reads commits since lastScanAt
       → for each commit: union of touched paths (.kt/.java/.md)
       → for each touched path: which .claude/wiki/ pages mention it (link text or referenced file path or canonical class name)
       → emits DriftEvent.CommitDrift entries (one per (page, commit-batch) pair)
       CodeRenameDetector emits CodeRename
       ManifestStaleDetector emits ManifestStale
       state.suggestions adds the librarian-recorded WikiSuggestion entries
  → events persisted to .drift-state.json
  → if autoUpdateWiki=true:
       background invoke Task(subagent_type="wiki-author", prompt=<events digest>)
       wiki-author writes/edits pages via propose_write/propose_edit
       (with autoAcceptEdits=true these apply silently; otherwise diff dialogs queue)
       on completion, append a one-line status to active chat (if any)
  → if autoUpdateWiki=false:
       drift banner appears (existing UI), user runs /refresh-wiki manually
       /refresh-wiki invokes Task(wiki-author, ...) instead of forwarding the prompt to the main agent
```

Three new components: `CommitWikiDriftDetector`, the `wiki-author` agent definition, and `WikiAuthorAgentArg`. Three reshaped: `DriftDetectionService`, `WikiLibrarianAgentArg` → `WikiAgentsArg`, `/refresh-wiki` handler. One deletion sweep: dream-related code.

## Component 1 — `wiki-author` subagent (`src/main/resources/agents/wiki-author.md`)

New plugin resource shipped alongside `wiki-librarian.md`. Same flat YAML frontmatter format that `WikiLibrarianAgentArg.parse()` already handles. Contents:

```markdown
---
name: wiki-author
description: Writes and edits this project's wiki under .claude/wiki/ in response to drift events. Receives a digest of CodeRename, ManifestStale, WikiSuggestion, and CommitDrift events; verifies each against current source; writes new concept pages or edits existing ones via propose_write/propose_edit. Runs in a fresh context — does not see the user's chat history. Invoked by /refresh-wiki and by the auto-update background task.
tools: Read, mcp__clawdea-intellij__read_wiki_page, mcp__clawdea-intellij__find_files, mcp__clawdea-intellij__find_symbol, mcp__clawdea-intellij__find_usages, mcp__clawdea-intellij__find_callers, mcp__clawdea-intellij__resolve_symbol, mcp__clawdea-intellij__search_text, mcp__clawdea-intellij__find_diagnostics, mcp__clawdea-intellij__propose_write, mcp__clawdea-intellij__propose_edit
---

You are this project's **wiki author**. You write and edit `.claude/wiki/` in response to a digest of drift events. You do not converse with the user; your only side effect is `propose_write`/`propose_edit` calls.

## Workflow

1. **Read the index first.** `read_wiki_page(name='index', kind='index')`. This is your map.
2. **Process events in order.** For each event in the digest:
   - **CodeRename** — open the wiki page, find the broken link, update it to the suggested replacement (verify the replacement actually exists via `find_files` / `find_symbol`), `propose_edit` the page. If no suggested replacement, search via `find_files` for the moved target; if you can't find it, remove the broken link.
   - **ManifestStale** — `propose_edit` the manifest to either update the path (if the repo moved) or remove the bullet (if the repo was deleted). Use `find_files` against parent directories to disambiguate.
   - **WikiSuggestion** — read `source_page` if present, verify the rationale against current source, then either:
     - `missingConcept` → `propose_write` a new page at the path under `.claude/wiki/concepts/`. Use the INVARIANT-FIRST template if the concept is a pipeline or runtime behaviour with non-obvious invariants; the NAVIGATION template otherwise. Templates are referenced below.
     - `staleConcept` → `propose_edit` the named page to bring it in line with current source.
     - `incompleteConcept` → `propose_edit` the named page to add the missing aspect.
     After writing/editing a concept page, also `propose_edit` `.claude/wiki/index.md` to add or update its TOC entry.
   - **CommitDrift** — open each named wiki page, read it, then verify each load-bearing claim against current source using `find_symbol` / `Read`. If a claim is contradicted by the post-commit state, `propose_edit` the page. If the commits removed an entire subsystem the page describes, `propose_edit` the page to remove or rewrite the affected section. **You decide whether the change matters.** If the commits don't actually invalidate the page (e.g. a rename of an internal helper that the wiki doesn't mention), do nothing for that event — say so in your final summary so the user knows you considered it.
3. **Keep the index current.** Any new concept page you write also updates `.claude/wiki/index.md`. Any concept page you delete is also removed from the index.
4. **Summarise.** End with a short plain-text list: which events you acted on, which you skipped (with reason), which pages you wrote/edited.

## Hard constraints

- No `Bash`, `Edit`, `Write`. Use `propose_write` and `propose_edit` exclusively. The built-in tools are not in your allowlist.
- Verify every load-bearing claim against current source. The wiki is supposed to be ground truth — do not propagate stale information.
- One page per concept. If a `missingConcept` suggestion's `target_files` lists multiple concept-page paths, write each one independently.
- Do not delete pages unless the digest explicitly says the underlying subsystem was removed. When unsure, edit the page to mark it stale rather than deleting.

## Templates

When writing a new concept page (missingConcept), use one of two templates depending on the concept's classification:

- `pipeline` or `runtime-behavior` (multi-step resolution, cache boundaries, registration order, runtime invariants the reasoner must hold) → INVARIANT-FIRST template.
- `navigation` (flat subsystem where a reader mainly needs to locate the right files) → NAVIGATION template.

The full template texts are inlined below at injection time. **Do not invent your own structure.** Match `/seed-wiki`'s output so all concept pages in `.claude/wiki/concepts/` share a consistent shape.

----- BEGIN INVARIANT-FIRST TEMPLATE -----
{{wiki-page-invariant}}
----- END INVARIANT-FIRST TEMPLATE -----

----- BEGIN NAVIGATION TEMPLATE -----
{{wiki-page-navigation}}
----- END NAVIGATION TEMPLATE -----

(`{{wiki-page-invariant}}` and `{{wiki-page-navigation}}` are replaced at agent-injection time by the canonical template texts from `src/main/resources/prompts/wiki-page-invariant.md` and `wiki-page-navigation.md`. See Component 2 for the templating mechanism.)

## Output shape

Plain text. No headings. Example:

> Acted on 4/6 events.
>
> - **CodeRename** in `wiki-librarian.md`: updated link from `WikiLibrarianInstaller` to `WikiLibrarianAgentArg`.
> - **WikiSuggestion (missingConcept)**: wrote `concepts/wiki-author.md` (NAVIGATION template) and added it to the index.
> - **CommitDrift** on `concepts/primer.md` (commits abc123..def456): no action — commits added a new primer source but the page describes the assembly order, which is unchanged.
> - **CommitDrift** on `concepts/wiki-librarian.md` (commit 841cb5a): rewrote the "installation" section to describe the `--agents` JSON injection path; removed the obsolete `WikiLibrarianInstaller` reference.
> - Skipped: ManifestStale `aem-cursor-toolkit` — repo path checks out; entry is correct.
> - Skipped: WikiSuggestion (incompleteConcept) on `concepts/drift.md` — could not verify the rationale against current source.
```

This file is bundled in the plugin jar (resources). It is not committed to project repositories — the `--agents` injection path delivers it per-session.

## Component 2 — `WikiAuthorAgentArg` (and renaming `WikiLibrarianAgentArg` → `WikiAgentsArg`)

`WikiLibrarianAgentArg` becomes `WikiAgentsArg`. The new class loads *both* resources and produces a single JSON object with two top-level keys:

```kotlin
object WikiAgentsArg {

    private const val LIBRARIAN_PATH = "/agents/wiki-librarian.md"
    private const val AUTHOR_PATH = "/agents/wiki-author.md"

    /**
     * Returns the JSON string for `--agents`, e.g.
     * `{"wiki-librarian":{...},"wiki-author":{...}}`.
     *
     * Throws [IllegalStateException] when either resource is missing or
     * malformed; callers should treat that as a packaging defect.
     */
    fun buildJson(): String { ... }

    /**
     * Returns the JSON string for `--agents` containing only the wiki-author
     * definition. Used by [DefaultWikiAuthorInvoker] for auto-apply subprocess
     * invocations where the librarian is irrelevant.
     */
    fun buildAuthorOnlyJson(): String { ... }

    // parse() unchanged from WikiLibrarianAgentArg.parse — same flat YAML rules.
}
```

**Template substitution.** The wiki-author resource (`/agents/wiki-author.md`) contains `{{wiki-page-invariant}}` and `{{wiki-page-navigation}}` placeholders. After parsing the YAML frontmatter and extracting the body, `WikiAgentsArg.buildJson()` (and `buildAuthorOnlyJson()`) substitutes each placeholder with the contents of the matching resource at `/prompts/<placeholder>.md` (loaded via the existing `PromptResource.load(name)`) before assembling the JSON. The librarian resource has no placeholders, so the substitution is a no-op for it.

The substitution runs once per `buildJson()` call. Because the prompt resources are bundled in the plugin jar, this is a hot-path read of static text — negligible cost.

`CliProcess.kt` line 125 (`command.addAll(listOf("--agents", agentsJson))`) is updated to call `WikiAgentsArg.buildJson()`. The log line becomes `"Injected wiki-librarian + wiki-author subagents via --agents (${agentsJson.length} chars)"`.

The flat-YAML parser in `WikiLibrarianAgentArg.parse()` already supports comma-separated `tools:`. The `wiki-author.md` resource above uses that format. The parser stays as-is, just gets called twice.

`enableWikiLibrarian=false` (the opt-out) skips the entire `--agents` injection — no librarian *and* no author. The legacy path (`search_wiki` registered, prose-prompt-to-main-agent on `/refresh-wiki`) takes over. This is the existing escape hatch and matches today's semantics.

`WikiLibrarianAgentArgTest` is renamed to `WikiAgentsArgTest`. Test cases:

- Parses both resources successfully and produces JSON with two top-level keys.
- Each inner object has `description`, `prompt`, `tools` fields.
- Throws `IllegalStateException` if either resource is missing.
- The librarian's `tools` array does NOT contain `propose_write` / `propose_edit`. (Catches accidental cross-contamination.)
- The author's `tools` array DOES contain `propose_write` / `propose_edit` and does NOT contain `record_wiki_suggestion`.

## Component 3 — `DriftEvent.CommitDrift` variant

Add to the existing `DriftEvent` sealed class in `src/main/kotlin/com/adobe/clawdea/knowledge/drift/DriftEvent.kt`:

```kotlin
data class CommitDrift(
    val wikiPage: Path,
    val commitShas: List<String>,    // commits since lastScanAt that touched a file the page references
    val touchedPaths: List<String>,  // project-relative paths touched by those commits, intersected with what the page mentions
    val firstObservedAt: String,     // ISO8601 — when the detector first saw this combination
) : DriftEvent() {
    override val signature: String =
        "commit-drift:${wikiPage.fileName}:${commitShas.joinToString(",").take(80)}"
}
```

The signature is intentionally batch-scoped: a new commit on a page that already has a pending `CommitDrift` produces a *new* event (different signature) so the wiki-author sees the latest state. When the wiki-author finishes, the user (or auto-apply) dismisses signatures via the existing `DriftStateStore.dismiss` path. A subsequent rescan with no new commits produces no event for that page.

The `Dream*` variants (`DreamIndexCleanup`, `DreamLinkNormalization`, `DreamSourceReferenceFix`, `DreamDuplicateConcept`, `DreamStaleConcept`, `DreamMissingConcept`) are deleted. No replacement; the wiki-author subsumes their handling because `CommitDrift` + `WikiSuggestion` + `CodeRename` + `ManifestStale` cover the cases that mattered in practice (link normalisation falls under `CodeRename`; stale concepts now come via `CommitDrift` or `WikiSuggestion`).

## Component 4 — `CommitWikiDriftDetector`

New file `src/main/kotlin/com/adobe/clawdea/knowledge/drift/CommitWikiDriftDetector.kt`. Pure-Kotlin / Git4Idea dependency — no LLM, deterministic.

```kotlin
object CommitWikiDriftDetector {

    /**
     * Reads commits since [lastScanAt] (inclusive of HEAD) on the current branch
     * via Git4Idea, intersects each commit's touched files with what
     * `.claude/wiki/concepts/*.md` mentions, and returns one [DriftEvent.CommitDrift]
     * per affected wiki page.
     *
     * "Mentions" means any of:
     *   - exact substring of a touched relative path (e.g. `src/main/kotlin/.../Foo.kt`)
     *   - basename match of a touched file (e.g. `Foo.kt`)
     *   - canonical class/symbol name extracted from a touched .kt/.java file (best-effort
     *     regex on `class|object|interface|enum class <Name>` + the filename without
     *     extension as fallback)
     *
     * Walks at most [MAX_COMMITS] commits and at most [MAX_TOUCHED_FILES] file
     * changes total to bound cost. If the limits are hit, the resulting event
     * still surfaces (the wiki-author can be asked to investigate) but logs a
     * warning.
     */
    fun detect(
        project: Project,
        wikiDir: Path,
        lastScanAt: Instant?,
        now: Instant,
    ): List<DriftEvent.CommitDrift> { ... }

    private const val MAX_COMMITS = 200
    private const val MAX_TOUCHED_FILES = 1_000
}
```

Implementation notes:

- Use Git4Idea's commit history API for the walk (e.g. `git4idea.history.GitHistoryUtils` or `git4idea.history.GitLogUtil`). Exact entry point to be confirmed in the implementation plan against the current Git4Idea version (the IntelliJ Platform 2026.1 build that ClawDEA targets); the spec deliberately does not pin a method signature.
- If `lastScanAt` is empty (first run), use a `--max-count=20` window — surfacing the last 20 commits as a baseline rather than all history.
- Walk `wikiDir` looking at `.md` files. For each, build a small `MentionIndex` per page. **False-positive control:** only index a touched-path token as a "mention" if it is one of:
  - a project-relative path starting with `src/`, `bin/main/` (Gradle), `build.gradle.kts`, `settings.gradle.kts`, or `gradle.properties`
  - a basename of length ≥ 6 with at least one uppercase letter (rules out matching `Path`, `List`, `id`, etc. in JDK classes the wiki may incidentally name)
  - the canonical class/object name of a `.kt` or `.java` file, extracted by regex `^(class|object|interface|enum class|enum)\s+([A-Z][A-Za-z0-9_]+)`
- Intersect: for each touched path token meeting the above rules, look it up in each page's mention set; collect (page → commit SHA) tuples. Group by page.
- Construct `CommitDrift` with the union of touched paths *that this page mentions*, not the full commit's touched-paths list, so the digest sent to the wiki-author is signal, not noise.

Cost ceiling: the `MentionIndex` is rebuilt per-rescan (the wiki dir is small — typically under 50 files, under 500 KB); the commit walk is bounded by `MAX_COMMITS = 200` and `MAX_TOUCHED_FILES = 1_000`. Worst-case is ~200 ms on a project with 50 commits since `lastScanAt` and a 30-page wiki.

Hooked into `DriftDetectionService.collectRaw`, gated on `enableWikiLibrarian`:

```kotlin
if (settingsState.enableWikiLibrarian) {
    out += CommitWikiDriftDetector.detect(
        project = project,                          // new parameter; service is already project-scoped
        wikiDir = claudeDir.resolve("wiki"),
        lastScanAt = parseInstantOrNull(beforeState.lastScanAt),
        now = now,
    )
}
```

Gating on `enableWikiLibrarian` keeps the legacy opt-out path (Component 6) clean: when the librarian is off, no `CommitDrift` events fire, and `/refresh-wiki` falls back to handling only `CodeRename`/`ManifestStale`/`WikiSuggestion` via the prose-for-main-agent flow.

`DriftDetectionService.rescan` already updates `lastScanAt` to `now.toString()` at the end of the cycle, so the next rescan picks up only newer commits.

**Trigger surface.** ClawDEA already has `FilesystemRefreshCoordinator` for VFS events but no equivalent for git refs. Add a `GitRepositoryChangeSubscriber` (project-level service) that subscribes to `GitRepository.GIT_REPO_CHANGE` topic in IntelliJ's message bus and calls `service.rescan()` on every notification, debounced 5s. This catches commits, fetches, pulls, branch switches, and rebases — every case where HEAD or a tracking ref changes — regardless of whether the user authored the change. Bounded cost: rescan reads only commits since `lastScanAt`, so a fetch with N new commits costs O(N) plus the wiki-page scan.

## Component 5 — `DriftDetectionService` reshape

Two changes:

1. **Drop dream paths.** Remove `runDreamScanNow`, `detectDreamEvents`, `cheapDreamDueCheck`, `observeCheapDreamSignals`, `acquireDreamLock`, `releaseDreamLock`, `isDreamFilesystemLockHeld`, `updateDreamState`, `MAX_CHEAP_SIGNAL_UNITS`, `MAX_OBSERVED_SIGNAL_UNITS`, `DREAM_LOCK_FILE`, `DREAM_LOCK_STALE_AFTER`. Remove the `runDreamScan: Boolean` param from `collectRaw` and `rescan`. The service file becomes ~150 lines lighter.

2. **Auto-apply via wiki-author.** The existing `applyAndDismiss(events, autoUpdateEnabled, ...)` is wrong for `CommitDrift` / `WikiSuggestion` / `CodeRename` / `ManifestStale` — none of these are statically applicable; they require the wiki-author. Replace its body with:

   ```kotlin
   internal fun applyAndDismiss(
       events: List<DriftEvent>,
       autoUpdateEnabled: Boolean,
       beforeState: DriftState,
       today: String,
       wikiAuthorInvoker: WikiAuthorInvoker = DefaultWikiAuthorInvoker,
   ): Pair<List<DriftEvent>, ApplyResult> {
       if (!autoUpdateEnabled || events.isEmpty()) {
           return events to ApplyResult(emptyList(), beforeState)
       }
       val invocation = wikiAuthorInvoker.invoke(events)  // returns acted-on signatures
       val applied = events.filter { it.signature in invocation.actedOnSignatures }
       val remaining = events - applied.toSet()
       val newState = beforeState.copy(dismissed = beforeState.dismissed + applied.map { it.signature })
       return remaining to ApplyResult(applied, newState)
   }
   ```

   ### Invoker contract

   `WikiAuthorInvoker` is a project-scoped interface:

   ```kotlin
   interface WikiAuthorInvoker {
       data class Result(val actedOnSignatures: Set<String>, val skippedSignatures: Set<String>, val errorMessage: String?)
       suspend fun invoke(events: List<DriftEvent>): Result
   }
   ```

   `DefaultWikiAuthorInvoker` (project service) spawns a `claude -p` subprocess on a worker thread (never EDT). The subprocess invocation:

   ```
   claude -p \
       --output-format stream-json \
       --no-session-persistence \
       --agents <author-only-json> \
       --disallowedTools "Edit,Write,Bash" \
       "@wiki-author <events digest>"
   ```

   - `--agents` carries the **author-only** JSON (a new `WikiAgentsArg.buildAuthorOnlyJson()` method). The librarian definition is irrelevant here.
   - `@wiki-author` is Claude Code's mention syntax for routing the prompt to a registered subagent.
   - `--disallowedTools` enforces the allowlist at the process boundary (defence in depth — the subagent's own `tools:` field is the primary gate).
   - `--output-format stream-json` is required so the invoker can observe `propose_write` / `propose_edit` tool-use events as they happen and derive `actedOnSignatures` from them.
   - Timeout: 5 minutes. The wiki-author may write multiple pages; bound the subprocess so a hung CLI doesn't block subsequent rescans.

   ### Deriving `actedOnSignatures` (open implementation question)

   The wiki-author's prompt instructs it to write/edit pages — but the *connection* between "the wiki-author's `propose_write` calls" and "which `DriftEvent.signature`s were thereby resolved" is not 1:1 from the subprocess output alone. Three options for the implementer:

   - **(a)** Parse the wiki-author's final summary (a structured plain-text list per the agent definition) and mark each `acted on:` line's signature as resolved. Requires the agent prompt to emit signatures verbatim — feasible (the digest builder hands them in already).
   - **(b)** Treat *all* events in the digest as resolved if the subprocess exits 0 with no error message. Coarser but simpler; the wiki-author's "skipped" entries become "dismissed but with no edit" — same outcome from the user's POV (the event won't re-fire because it's signature-dismissed).
   - **(c)** Mark events resolved only if their target file appeared in a `propose_*` call observed in the stream. Most precise but requires correlating events to file paths.

   **Decision: start with (b).** It's the simplest correct contract, and the cost of an over-dismissal is bounded — a `staleConcept` that the wiki-author skipped will re-fire only if the underlying source changes (the `CommitDrift` signature is commit-batch-scoped; a new commit produces a fresh signature). If precision matters in practice, escalate to (a) by tightening the agent's output contract.

   The wiki-author's existing `tools:` allowlist is `propose_write,propose_edit` for writing — these surface as MCP tool calls in `--output-format stream-json`, so the invoker can log them for observability even when using strategy (b).

3. **Active-chat status note on auto-apply completion.** When `DefaultWikiAuthorInvoker` finishes, post a one-line note via the existing `ChatPanel.appendHtml(renderer.renderInfoMessage(...))` path. Routing: post to **all open ChatPanels for this project** (the project may have multiple chats; users opening a second chat should still see the most recent auto-apply outcome). The note format mirrors the "(no drift events detected)" message:

   > Auto-applied wiki updates from drift events: 3 acted on, 1 skipped. See `.claude/wiki/.drift-state.json` for details.

   No popup, no diff dialogs (those happen inline via `propose_*` if `autoAcceptEdits=false`). If no chat panel is open for the project, the message is dropped — the user finds the result on their own when they open one. The drift state JSON is the durable record.

## Component 6 — `/refresh-wiki` reshape (manual path, `autoUpdateWiki=false`)

Today (`ChatPanel.kt:1208-1228`):

```kotlin
private fun refreshWiki(args: RefreshWikiArgs): RefreshWikiResult {
    ...
    val (events, _) = if (args.forceDream) service.runDreamScanNow() else service.rescan()
    if (args.applyLowRisk) return Local(formatAppliedWikiDrift(...))
    if (events.isEmpty()) return Local("(no drift events detected)")
    return ReviewPrompt(buildRefreshWikiPrompt(events))    // <-- forwards to main agent
}
```

After:

```kotlin
private fun refreshWiki(args: RefreshWikiArgs): RefreshWikiResult {
    val service = project.getService(DriftDetectionService::class.java)
    val (events, _) = service.rescan()                     // forceDream removed with rest of dream code
    if (events.isEmpty()) return Local("(no drift events detected)")
    return WikiAuthorPrompt(buildWikiAuthorDigest(events)) // new RefreshWikiResult variant
}
```

`WikiAuthorPrompt` is dispatched the same way `ReviewPrompt` is today (`dispatchOrQueueRefreshPrompt`), but the prompt body is `"@wiki-author <digest>"` rather than the prose-for-main-agent that `buildRefreshWikiPrompt` produced. The CLI sees `@wiki-author` and routes the prompt to the subagent.

`buildWikiAuthorDigest(events)` produces:

```text
Acting on these drift events. Process them in order.

- CommitDrift on .claude/wiki/concepts/wiki-librarian.md
  commits: 841cb5a, baab575
  touched paths that this page mentions: src/main/kotlin/com/adobe/clawdea/knowledge/wiki/WikiLibrarianInstaller.kt, src/main/kotlin/com/adobe/clawdea/cli/CliProcess.kt
  first observed at: 2026-05-17T16:30:00Z

- CodeRename in .claude/wiki/concepts/edit-review.md
  broken link: src/main/kotlin/.../OldClass.kt
  suggested replacement: src/main/kotlin/.../NewClass.kt

- WikiSuggestion (missingConcept): Add concept page for wiki-author subagent
  rationale: <librarian-recorded rationale>
  target files: .claude/wiki/concepts/wiki-author.md, .claude/wiki/index.md
  observed while reading: .claude/wiki/concepts/wiki-librarian.md

(continues for each event)

After acting, summarise which events you handled and which you skipped (with reason).
```

The existing `buildRefreshWikiPrompt` (which produced the prose-for-main-agent format) is **deleted**. With the librarian off (`enableWikiLibrarian=false`), the legacy path is preserved by routing through a `buildLegacyRefreshWikiPrompt` extracted from today's body, but with the dream variants gone — only `CodeRename`, `ManifestStale`, and `WikiSuggestion` need handling, since `CommitDrift` only fires when the librarian is on (it's part of the new architecture). This keeps the opt-out semantically clean: if you turn the librarian off, you also don't get commit-driven detection.

`RefreshWikiArgs.forceDream` is removed. `RefreshWikiArgsTest` is updated to reflect that.

## Component 7 — `/seed-wiki` cap removal (librarian-on case)

`ChatPanel.kt:1060` currently emits:

> identify 5–10 concept areas worth documenting (main subsystems, key APIs, active feature work, architectural decisions worth capturing).

Rewrite to:

> identify all concept areas worth documenting (main subsystems, key APIs, active feature work, architectural decisions worth capturing). Err on the side of more focused pages over fewer dense ones — there is no upper bound. A 200-file project might have 5 concepts; a 5,000-file project might have 50.

Gate the rewritten text on `ClawDEASettings.enableWikiLibrarian`. When the librarian is off, preserve today's "5–10" wording verbatim. Single-flag check at the start of the `BridgeExpandingHandler` closure body.

## Component 8 — Settings cleanup

Remove from `ClawDEASettings.State`:

- `enableDreamWikiMaintenance`
- `dreamWikiMinElapsedHours`
- `dreamWikiMinSignalUnits`
- `dreamWikiScanThrottleMinutes`

Remove from `DriftState`:

- `dreamLastRunAt`
- `dreamLastSuccessfulScanAt`
- `dreamLastFailedScanAt`
- `dreamLastDueCheckAt`
- `dreamLastStatus`
- `dreamProcessedSignalUnits`
- `dreamObservedSignalUnits`
- `dreamFilteredCandidateCount`
- `dreamLockOwner`
- `dreamLockAcquiredAt`

`DriftStateStore.read` uses Gson with default values, so older `.drift-state.json` files containing dream fields will deserialize cleanly (extra JSON fields are ignored by Gson when the data class has no matching property). New writes simply omit the fields.

Settings panel rows for the four dream fields are removed.

## Component 9 — Code deletions

Files to delete entirely:

- `src/main/kotlin/com/adobe/clawdea/knowledge/drift/ClaudeDreamInvocation.kt`
- `src/main/kotlin/com/adobe/clawdea/knowledge/drift/DreamInvocation.kt` (interface + helpers; no other implementer)
- `src/main/kotlin/com/adobe/clawdea/knowledge/drift/DreamWikiDetector.kt`
- `src/main/kotlin/com/adobe/clawdea/knowledge/drift/DreamWikiSettings.kt` *(if exists as separate file; otherwise the `data class DreamWikiSettings` declaration is dropped from wherever it lives)*
- `src/main/kotlin/com/adobe/clawdea/knowledge/drift/DreamDueGate.kt`
- `src/main/kotlin/com/adobe/clawdea/knowledge/drift/DreamCandidateScorer.kt`
- `src/main/kotlin/com/adobe/clawdea/knowledge/drift/DreamCandidate.kt` *(and its associated kind/cost/confidence enums)*
- `src/main/kotlin/com/adobe/clawdea/knowledge/drift/DreamOutputValidator.kt`
- `src/main/kotlin/com/adobe/clawdea/knowledge/drift/DreamEventMapper.kt`
- All matching `*Test.kt` files under `src/test/`.

`DriftEvent` loses the six `Dream*` variants. `ChatPanel.buildRefreshWikiPrompt` loses the matching `when` branches.

Mechanical-deletion-only commit, separate from the new code. PR review-friendly.

## Component 10 — Documentation

`docs/user-guide.md` updates:

- The "Dream wiki maintenance" section is replaced with a "Commit-driven wiki maintenance" section explaining the new flow: `CommitWikiDriftDetector` runs on every git ref change, `wiki-author` writes pages, `Auto-update wiki on drift` controls whether updates are silent.
- The settings list loses the four dream fields.

`docs/comparison/Augment.md` line 10 (the row about wiki maintenance) is updated to drop the `learn-on-probe-miss` claim if it referred to dream-spotted candidates and instead describe the commit-driven flow.

The 2026-05-16 librarian spec gets a `Status: Implemented (with deviations — see 2026-05-17-wiki-maintenance-redesign-design.md)` header so future readers know to check the newer spec for the current state.

## Future work — Anthropic Dreams integration

When the Anthropic Dreams research-preview becomes publicly available (today it requires application access at `https://claude.com/form/claude-managed-agents`), wiki maintenance can ingest dream digests. The shape:

- ClawDEA's session transcripts and the user's project memory store (an Anthropic Memory Store, separate from the project-local wiki) are dreamed via `client.beta.dreams.create(...)`.
- The dream output is a curated memory store containing reorganised, deduplicated, contradiction-resolved facts the agent has learned across sessions.
- A new `DreamDigestImporter` reads the curated memory store, extracts facts that are *project-specific* (heuristic: filter against the project root, the wiki's covered concepts, or by tag), and feeds them as additional inputs to the wiki-author alongside the drift events digest.
- The wiki-author's existing workflow handles them: verify against current source, propose a new concept page or edit, summarise.

This unlocks **knowledge that emerged in chats but was never committed to the wiki** — the failure mode the brainstorming session that produced this spec tried to address. Until research-preview goes public, ClawDEA cannot use it.

The Dreams beta requires:

- Beta headers `managed-agents-2026-04-01` and `dreaming-2026-04-21`.
- An Anthropic Memory Store to dream over.
- 1–100 session transcripts (must be Anthropic-stored; CLI sessions persist locally by default and are not eligible). Switching to Anthropic-stored sessions is itself a meaningful architectural change.

These costs make the feature only worth it if dream output quality materially improves what the commit-driven detector + wiki-author already provide. Re-evaluate when the API becomes public.

## Testing

Pure-Kotlin unit tests, run by `./gradlew test`. Headless-safe.

**New tests:**

- `WikiAgentsArgTest` — replaces `WikiLibrarianAgentArgTest`. Cases listed in Component 2.
- `CommitWikiDriftDetectorTest` — fixture-based: a small in-memory git history, a wiki dir with seeded mention patterns, asserts the detector's output matches expected `CommitDrift` events. Runs from the IDE test runner only (uses `LightJavaCodeInsightFixtureTestCase` for project setup) — excluded from headless `./gradlew test` per the existing pattern.
- `DriftEventTest` (extend) — `CommitDrift.signature` correctness: stable for the same (page, commits) pair, differs across pages, differs across commit batches.

**Updated tests:**

- `DriftDetectionServiceTest` — drops dream-path test cases. Asserts new auto-apply path: when `autoUpdateWiki=true` and `events.isNotEmpty()`, `WikiAuthorInvoker` is called with the events; signatures it returns are dismissed.
- `DriftStateStoreTest` — drops dream-field round-trip cases.
- `RefreshWikiArgsTest` — drops `--force-dream`.
- `WikiIndexSourceTest` — unchanged (this design doesn't touch the librarian directive).
- Settings panel test — dream rows removed.

**Tests not in scope:**

- Wiki-author behavioural output (LLM, not deterministic).
- Live `--agents` injection (covered by `CliProcessTest` for the existing librarian flag; the only change is the JSON content).
- Real Dreams API (deferred).

**Manual smoke checklist:**

1. Project with a wiki and recent commits → open ClawDEA → drift banner shows `CommitDrift` events for pages whose mentioned files were touched.
2. `Auto-update wiki on drift` off → click `/refresh-wiki to review` → wiki-author prompt fires (subagent name visible in tool-use stream); diffs surface for accept/reject.
3. `Auto-update wiki on drift` on → wait ~10s after a `git pull` that touches a wiki-mentioned file → status note appears in active chat ("Auto-applied wiki updates: N acted on, M skipped"); `.drift-state.json` has the dismissed signatures.
4. Set `enableWikiLibrarian=false` → no `CommitDrift` events surface; `/refresh-wiki` falls back to the prose-for-main-agent path with only `CodeRename`/`ManifestStale`/`WikiSuggestion`.
5. `/seed-wiki` on a fresh project → Claude proposes more than 10 concept pages if the project warrants it.
6. Old `.drift-state.json` (containing dream fields) → reads cleanly, fields ignored.
