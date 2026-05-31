---
name: sync-upstream
description: >
  Sync the LibreChat Mobile client with a newer official LibreChat server release.
  Diffs upstream tags, identifies gaps in the mobile app, proposes changes with
  user approval, then implements them. Use when a new LibreChat version is released.
allowed-tools: Bash, Read, Glob, Grep, Write, Edit, WebFetch, WebSearch, Agent, TeamCreate, SendMessage, TaskCreate, TaskUpdate, TaskGet, TaskList
argument-hint: "[target-tag] (optional, defaults to latest stable)"
---

# Sync Upstream

Synchronize the LibreChat Mobile app with a newer version of the official LibreChat server.
This is a multi-phase, team-based workflow.

You are the **team lead**. You orchestrate at a high level. You NEVER read code, write code,
or grep the codebase yourself. All heavy lifting is delegated to teammates.

## Team Setup

Create an Agent Team with 5 teammates at the start:

| Teammate | Role | Prompt Summary |
|----------|------|----------------|
| **investigator** | Reads upstream diffs, analyzes API/type/UI changes, cross-references with mobile codebase | "You are the investigator for a LibreChat Mobile upstream sync. Your job is to analyze diffs between upstream tags and identify gaps in the mobile client. You have read-only responsibilities — never write code. You have WebFetch and WebSearch access so you can read GitHub release notes and changelogs in addition to local diffs." |
| **android-expert** | Technical advisor on Android architecture and best practices. Cross-checks all guidance with online research before advising. | "You are the Android tech lead for a LibreChat Mobile upstream sync. You provide guidance on Android best practices, Jetpack Compose patterns, Kotlin idioms, and architectural decisions. CRITICAL: You MUST cross-check your knowledge by researching online (WebSearch/WebFetch) before giving advice. Never rely solely on training data — always verify current best practices, especially for Compose, Ktor, Koin, and Material 3. Return researched guidance to teammates who ask." |
| **ios-kmp-expert** | Technical advisor on iOS and Kotlin Multiplatform architecture. Ensures KMP shared code and iOS-specific implementations follow platform conventions. | "You are the iOS/KMP tech lead for a LibreChat Mobile upstream sync. You provide guidance on Compose Multiplatform patterns, KMP expect/actual declarations, iOS platform APIs (UIKit, Foundation, Keychain, Network.framework), SKIE interop, and ensuring shared code compiles correctly for both targets. CRITICAL: You MUST cross-check your knowledge by researching online (WebSearch/WebFetch) before giving advice. Never rely solely on training data — always verify current best practices for CMP, KMP, and iOS platform conventions. Return researched guidance to teammates who ask." |
| **implementer** | Writes code changes to mobile app following existing patterns and conventions | "You are the implementer for a LibreChat Mobile upstream sync. Your job is to write code changes following existing architecture patterns. Always read the module's CLAUDE.md before touching it. This project uses **Koin** for DI (NOT Hilt) — use `koinViewModel()`, `viewModelOf(::X)`, `singleOf(::X)`, and Koin modules in `feature/*/di/`. Follow safeApiCall, UDF, Ktor, @Serializable patterns. Whenever you touch a KMP module, check BOTH commonMain AND iosMain source sets — expect declarations in commonMain need actual implementations in both androidMain and iosMain. Before making significant architectural decisions, consult the android-expert or ios-kmp-expert teammate." |
| **verifier** | Checks implementation correctness, runs builds, validates changes match proposal | "You are the verifier for a LibreChat Mobile upstream sync. Your job is to independently validate that implementation matches the approved proposal. Run Android build, iOS framework link, detekt, and tests. Review changes file-by-file. Flag issues. Message the implementer directly if fixes are needed — they retain context of what they changed." |

**Teammate interaction pattern:**
- **investigator** works independently on Phase A and B (diff + gap analysis)
- **android-expert** is consulted for Android-specific architecture, Compose, and Material 3 decisions.
  The android-expert MUST search online to verify recommendations — do not accept advice that
  hasn't been cross-checked against current documentation.
- **ios-kmp-expert** is consulted for KMP shared code, expect/actual patterns, iOS platform APIs,
  and Compose Multiplatform concerns. Same online-verification requirement as android-expert.
- **implementer** does all code changes, consulting android-expert or ios-kmp-expert for non-trivial patterns
- **verifier** validates after implementer finishes, messages implementer for fixes

**Important:** Teammates retain context across messages. Reuse them for follow-ups — do NOT
spawn new sub-agents (sub-agents lose context). Sub-agents are a last resort fallback only.

## Prerequisites

Run these checks yourself (lightweight, no codebase reading). The working directory is already
the mobile repo root — do **not** `cd` into a different directory. The local checkout directory
name can vary (historical: `LibreChat-Android`; current upstream: `Librechat-Mobile`), so rely
on relative paths from CWD rather than any hardcoded repo-dir name.

### 1. Submodule check
```bash
git submodule status
```
If `upstream/` is missing:
```bash
git submodule add https://github.com/danny-avila/LibreChat.git upstream
git submodule update --init
```

### 2. Version file check
```bash
cat UPSTREAM_VERSION
```
If missing, create from current state:
```bash
VERSION=$(grep '^backendTargetVersion=' version.properties | cut -d= -f2 | tr -d '[:space:]')
COMMIT=$(cd upstream && git rev-parse HEAD)
echo "# Upstream LibreChat version this mobile build tracks." > UPSTREAM_VERSION
echo "# Updated by the sync-upstream skill. Do not edit manually." >> UPSTREAM_VERSION
echo "tag=v${VERSION}" >> UPSTREAM_VERSION
echo "commit=${COMMIT}" >> UPSTREAM_VERSION
echo "date=$(date +%Y-%m-%d)" >> UPSTREAM_VERSION
```

### 3. Clean working tree
```bash
git status --porcelain
```
If dirty, ask the user to commit or stash before proceeding.

### 4. Context recovery (new session detection)

Check if a prior sync was partially done in another session:
```bash
TRACKED_TAG=$(grep '^tag=' UPSTREAM_VERSION | cut -d= -f2)
CODE_VERSION=$(grep '^backendTargetVersion=' version.properties | cut -d= -f2 | tr -d '[:space:]')
SUBMODULE_HEAD=$(cd upstream && git rev-parse HEAD)
TRACKED_COMMIT=$(grep '^commit=' UPSTREAM_VERSION | cut -d= -f2)
```

If `TRACKED_TAG` (without `v` prefix) != `CODE_VERSION`, or `SUBMODULE_HEAD` != `TRACKED_COMMIT`:
> "It looks like a sync may have been partially done. UPSTREAM_VERSION says {TRACKED_TAG}
> but version.properties (backendTargetVersion) says {CODE_VERSION}. Should I treat the
> higher version as the current baseline, or would you like to investigate?"

Wait for user response before continuing.

Also check for existing artifacts from a previous partial run:
```bash
ls -la .claude/sync-upstream/artifacts/ 2>/dev/null
```
If a previous proposal or phase report exists, offer to resume from that state.

### 5. Prepare artifact directory

```bash
mkdir -p .claude/sync-upstream/artifacts
```
All phase outputs (diff summaries, gap reports, proposals) are persisted here so the skill
is resumable if the session is compacted or dies mid-run.

---

## Phase A: Diff Analysis

**Delegate to: investigator**

### A1. Assign investigation task

Create a task for the investigator with this prompt:

> Analyze upstream changes for a version sync. **Persist all outputs to files under
> `.claude/sync-upstream/artifacts/` as you go** so the team lead can recover state if
> the session is compacted.
>
> 1. Read `UPSTREAM_VERSION` at the repo root to get the current tracked tag and commit.
> 2. Fetch latest tags: `cd upstream && git fetch --tags origin`
> 3. List all tags newer than the current tracked tag:
>    `cd upstream && git tag --sort=-v:refname`
>    Filter OUT any tags containing `-rc`, `-beta`, `-alpha`.
> 4. **Read GitHub release notes for every stable tag** between the current tracked tag and
>    the target tag (inclusive). Release notes almost always flag breaking changes that
>    diffs hide. For each tag:
>    ```bash
>    gh api repos/danny-avila/LibreChat/releases/tags/{tag} --jq .body
>    ```
>    Fall back to `WebFetch` on `https://github.com/danny-avila/LibreChat/releases/tag/{tag}`
>    if `gh` is unavailable. Also scan commit messages:
>    `cd upstream && git log --oneline {current_tag}..{target_tag}` — commit subjects
>    from upstream maintainers are often the most candid description of what broke.
> 5. Generate diffs between the current tag and {target_tag} for all paths listed in
>    `${CLAUDE_SKILL_DIR}/reference/upstream-paths.md`. Use `--stat` for an overview,
>    then request per-path file-level diffs for anything non-trivial. Paths include:
>    - `api/server/routes/` (including `routes/agents/`, `routes/files/`, `routes/admin/`)
>    - `api/server/controllers/`, `api/server/middleware/`, `api/server/services/`
>    - `api/models/` (Mongoose models that shape API responses)
>    - `packages/data-provider/src/` — especially `api-endpoints.ts`, `config.ts`,
>      `data-service.ts`, `parsers.ts`, `permissions.ts`, `types/`, `react-query/`
>    - `packages/data-schemas/src/`
>    - `packages/api/src/` (newer workspace introduced recently)
>    - `client/src/components/`, `client/src/hooks/`, `client/src/data-provider/`,
>      `client/src/store/`, `client/src/Providers/`
>    - `librechat.example.yaml`, `.env.example` (server config schema — affects `/api/config` surface)
> 6. Categorize every change into exactly one of:
>    - **API changes**: New or modified routes, controller logic
>    - **Removed/deprecated endpoints**: Routes or fields that were removed or deprecated (BREAK mobile hard — cannot be deferred)
>    - **Type/schema changes**: New or modified data types, request/response shapes
>    - **New config / feature flags**: New keys in `/api/config` response that gate UI
>    - **UI changes**: New components, modified user flows
>    - **Security fixes**: CVEs, auth changes, token handling — cannot be deferred
>    - **Bug fixes**: Non-security fixes that may need mobile equivalents
>    - **Infrastructure**: Build, config, deps (usually not relevant)
> 7. Write the categorized summary to
>    `.claude/sync-upstream/artifacts/phase-a-diff-summary.md` and report a short
>    version back. Include representative file paths and release-note excerpts.

If `$ARGUMENTS` was provided, use it as the target tag.

### A2. Present results to user

Once investigator reports back:
- If no newer tags exist, tell user "Already up to date with {current_tag}" and stop.
- If no `$ARGUMENTS`, present the list of available tags and ask user to pick one (default: latest stable).
- Show the investigator's categorized summary.

**Done when:** User has seen the diff summary and confirmed the target tag. The file
`.claude/sync-upstream/artifacts/phase-a-diff-summary.md` exists.

---

## Phase B: Gap Analysis

**Delegate to: investigator** (reuses context from Phase A — do NOT spawn a new agent)

### B1. Send follow-up to investigator

> Now cross-reference the upstream changes you found with the mobile codebase.
> Persist the gap report to `.claude/sync-upstream/artifacts/phase-b-gap-report.md`.
>
> 1. Read these mapping files from `${CLAUDE_SKILL_DIR}/reference/`:
>    - `api-mapping.md` — official routes to mobile `*Api.kt` files
>    - `model-mapping.md` — official TS types to Kotlin data classes
>    - `ui-mapping.md` — web components to mobile feature modules
> 2. Read `DISCOVERY.md` at the repo root — it catalogs already-discovered backend
>    endpoints and their quirks. Any new endpoints must be appended there by the
>    implementer in Phase D.
> 3. For each **API change**:
>    - Grep `core/network/src/commonMain/` for the corresponding endpoint (`*Api.kt` files)
>    - Also grep `core/data/src/commonMain/` — some repositories inline endpoint paths
>    - Note: exists / needs updating / missing
> 4. For each **removed / deprecated endpoint**:
>    - Find every mobile call site and flag it as a Breaking gap
> 5. For each **type / schema change**:
>    - Check `core/model/src/commonMain/` for the matching Kotlin data class
>    - Note new, removed, or type-changed fields
>    - **SSE-specific**: when upstream modifies streaming payloads or run events, also
>      check `core/model/src/.../StreamEvent.kt`, `SseContentEvent.kt`, `ToolCallRecord.kt`,
>      `ToolCallResult.kt`, `ToolAuthStatus.kt`. SSE shape drift is a frequent source of
>      silent breakage that compiles but produces wrong UI at runtime.
> 6. For each **UI change**:
>    - Check the corresponding `feature/*/` module (commonMain + platform sources)
>    - Note if the feature exists, needs updating, or is missing
> 7. For each **new feature flag in `/api/config`**:
>    - Check `core/model/src/commonMain/.../StartupConfig.kt` — new keys must be added
>      for the mobile app to detect the flag and gate UI accordingly
> 8. **Version-gating check.** Read `core/common/src/commonMain/.../BackendVersion.kt`.
>    It exposes `parse()`, `isCompatible()`, and `extractVersionFromFooter()`. For any
>    new upstream feature that requires the target tag or higher, flag it so the
>    implementer adds a `BackendVersion.isCompatible(...)` check on the mobile side.
> 9. **iosMain coverage check.** For every touched module under `core/*` or `feature/*`,
>    confirm whether `src/iosMain/kotlin/...` sources exist. If they do, the implementer
>    must update both commonMain AND iosMain (expect/actual pairs, platform-specific
>    bridges like `NWConnection` SSE transport, Keychain, etc.). Flag modules where
>    iosMain parity is easily missed.
> 10. Categorize all gaps:
>     - **Breaking** (must fix): Changed or removed request/response shapes, removed/renamed
>       endpoints, auth changes, security fixes
>     - **Additive** (new feature): New endpoints, new UI features, new optional fields,
>       new feature flags
>     - **Cosmetic** (polish): UI improvements, a11y, i18n
> 11. Report the categorized gap list with specific file paths for both upstream and mobile.

### B2. Receive gap report

The investigator now has full context of both the upstream diff AND the mobile gaps.
Keep the investigator alive for follow-up questions during Phase C.

**Done when:** You have the categorized gap list, and
`.claude/sync-upstream/artifacts/phase-b-gap-report.md` exists on disk.

---

## Phase C: Proposal

**Lead presents to user — no delegation needed.**

Take the investigator's gap report and produce a structured proposal.
**Persist the proposal to `.claude/sync-upstream/artifacts/proposal-{target_tag}.md`
before presenting it** so the user has a reviewable artifact and the skill can
resume if the session dies.

```
## Sync Proposal: {current_tag} → {target_tag}

### Breaking Changes ({count}) — must fix before updating version
For each: what changed upstream (file reference), mobile file(s) needing updates, proposed change.

### Security Fixes ({count}) — must fix, cannot be deferred
For each: CVE / patch description, mobile-side impact (if any), proposed change.

### Removed / Deprecated Endpoints ({count}) — mobile call sites must migrate
For each: removed/deprecated route, mobile call sites (file paths), replacement route and migration plan.

### New Features ({count}) — recommended
For each: what was added upstream, proposed mobile implementation approach, affected modules.

### New Config / Feature Flags ({count})
For each: new key in `/api/config`, proposed `StartupConfig.kt` update, whether a `BackendVersion` gate is needed.

### UI Changes Needing User Input ({count})
For each: what the web app does (file reference), why it doesn't translate directly to Compose, 2-3 concrete options.

### Deferred Items ({count}) — can wait for a future sync
Items that are low priority or require significant new infrastructure.
```

### Consult experts on non-trivial items

Before presenting to the user, for any proposed change that involves:
- New architectural patterns (new module, new DI scope, new navigation key)
- UI patterns without an existing equivalent in the codebase
- Performance-sensitive changes (list rendering, image loading, SSE throughput)
- KMP shared code or expect/actual patterns
- iOS-specific bridges (Network.framework, Keychain, WKWebView)

Send Android-specific items to **android-expert** and KMP/iOS items to **ios-kmp-expert**:

> Review these proposed mobile changes for best practices. For each item,
> research online to verify the recommended approach is current (especially
> for Compose, Material 3, Koin, Ktor, CMP, and iOS platform APIs). Flag any
> items where modern best practices differ from what we're proposing.
> {list of non-trivial items}

Incorporate the experts' feedback into the proposal **and update the artifact file** before showing the user.

### Approval gate

Ask the user:

> Review the proposal above (also saved at `.claude/sync-upstream/artifacts/proposal-{target_tag}.md`).
> Reply with:
> - **approve all** — proceed with full implementation
> - **approve with changes** — tell me what to modify in the proposal
> - **approve partial** — list which items to implement now (rest deferred)
> - **cancel** — abort, no changes made

**Do NOT proceed to Phase D until the user explicitly approves.**

If the user requests changes, update the proposal file AND re-present.

---

## Phase D: Implementation

**Delegate to: implementer, then verifier**

> **HARD RULE — Phase D ends at local commits. DO NOT push. DO NOT open a PR.**
>
> The verifier's gates (build + detekt + tests) confirm scaffolding correctness,
> not feature correctness. A sync touches dozens of files across endpoints, SSE
> shapes, UI wires, and backward-compat gates — it must be device-tested against
> a real backend on BOTH Android and iOS (including older-server compat cases)
> before a PR is offered for review.
>
> The implementer works on a feature branch named `chore/sync-upstream-{target_tag}`
> and commits per P/D group. After the verifier greenlights, the team-lead
> presents the test plan to the user. The user drives device testing, asks for
> follow-up fixes as more commits on the same local branch, and decides when
> (if ever) to open the PR. The team does NOT run `git push`, `git push -u`,
> or `gh pr create` at any point during `/sync-upstream`.

### D1. Assign implementation task

Create a task for the implementer with the approved change list:

> Implement the following approved changes for the upstream sync from {current_tag} to {target_tag}.
>
> **Before touching any module:**
> 1. Read the root `CLAUDE.md` for architecture rules
> 2. Read `${CLAUDE_SKILL_DIR}/reference/android-architecture.md` for patterns
> 3. Read the specific module's `CLAUDE.md` (e.g., `core/network/CLAUDE.md`)
> 4. Read existing similar code as a pattern reference
>
> **Conventions (THIS PROJECT USES KOIN, NOT HILT):**
> - `safeApiCall` for all network calls in repositories
> - **Koin** for DI — use `viewModelOf(::X)`, `singleOf(::X)`, `factoryOf(::X)` in Koin modules at `feature/*/di/` and `core/*/di/`. Register the module in `LibreChatApplication.kt`'s `startKoin { modules(...) }` when creating a new one.
> - Use `koinViewModel()` in Composables — never `hiltViewModel()` or `@HiltViewModel`.
> - Unidirectional data flow: UI → ViewModel → Repository → API/Room
> - `@Serializable` data classes in `core/model/`
> - Ktor client patterns in `core/network/`
> - For KMP modules, update BOTH `src/commonMain/` AND `src/iosMain/` (and `src/androidMain/` when relevant). `expect` declarations in commonMain REQUIRE matching `actual` implementations in every target's source set.
>
> **Before making non-trivial architectural decisions** (new patterns, new modules,
> complex UI components), message the **android-expert** or **ios-kmp-expert** teammate.
> They will research current best practices online and advise.
>
> **Approved changes:**
> {paste the approved change list here}
>
> **Branching:** Create a feature branch `chore/sync-upstream-{target_tag}` at the
> start. Commit per P/D group (one commit per must-ship item, one per deferred
> item). Use conventional-commit subjects (e.g. `feat(chat): render SUMMARY
> content blocks (D2)`).
>
> **After all code changes:**
> 1. Update `backendTargetVersion` in the root `version.properties` to `{new_version}` (without `v` prefix). This is the single source of truth — a Gradle task code-generates `BackendVersion.SUPPORTED_BACKEND_VERSION` from it, and `release.yml` reads the same key. Do **not** edit the `BackendVersion.kt` literal (it no longer exists).
> 2. Advance submodule: `cd upstream && git checkout {target_tag}`
> 3. Update `UPSTREAM_VERSION` at repo root:
>    ```
>    tag={target_tag}
>    commit={full SHA of target_tag}
>    date={today's date YYYY-MM-DD}
>    ```
> 4. Update `DISCOVERY.md` at the repo root: append newly-discovered endpoints, removed
>    endpoints, and revised response shapes. Keep it accurate — future syncs depend on it.
> 5. Create/update `VERSION_GATES.md` at repo root: add a row for any code path
>    that branches on `BackendVersion.isCompatible()` / `isCompatibleOrNewer()`.
>    Keep backward-compat gates auditable so future floor-raises are mechanical.
> 6. Report back the full list of files created or modified, grouped by module.
>
> **DO NOT** run `git push`, `git push -u origin ...`, or `gh pr create`.
> The branch stays local-only until the user device-tests and explicitly
> authorizes the push.

### D2. Assign verification task

After implementer reports done, create a task for the verifier:

> Verify the upstream sync implementation from {current_tag} to {target_tag}.
>
> **Build verification (run all four):**
> 1. `./gradlew assembleDebug` — Android app compiles
> 2. `./gradlew detekt` — static analysis passes on ALL source sets (commonMain,
>    androidMain, iosMain). The CI gate currently only covers commonMain, so do
>    not rely on CI here. If failures are found in iosMain/androidMain, report them.
> 3. `./gradlew :shared:linkDebugFrameworkIosSimulatorArm64` — iOS framework links.
>    This catches iosMain compile errors that `assembleDebug` silently skips.
> 4. `./gradlew check` — unit tests pass for touched modules
>
> **Correctness review:**
> 5. Read the approved proposal at `.claude/sync-upstream/artifacts/proposal-{target_tag}.md`.
> 6. Review every file the implementer changed:
>    - All approved items implemented?
>    - Any unapproved changes (scope creep)?
>    - Koin DI used (not Hilt / `@Inject`)?
>    - `safeApiCall` wrapping new network calls?
>    - For KMP modules: did the implementer touch iosMain where needed, not just commonMain?
>    - For new endpoints: was `DISCOVERY.md` updated?
>
> **Version consistency:**
> 7. `backendTargetVersion` in the root `version.properties` matches target (this drives the generated `SUPPORTED_BACKEND_VERSION`)
> 8. `UPSTREAM_VERSION` file has correct tag, commit, date
> 9. Submodule is at the correct tag: `cd upstream && git describe --tags`
> 10. `DISCOVERY.md` reflects endpoint additions/removals
>
> **If you find issues**, message the **implementer** directly to fix them.
> The implementer retains context of what it changed.
>
> **Report back:**
> - Pass/fail for each of the four build commands + any errors
> - Issues found (if any) and who fixed them
> - List of user-testable behaviors to verify on device — separate lists for Android and iOS where applicable

### D3. Independent reviewer pass

After the verifier signs off, spawn a fresh `reviewer` teammate (independence is
the point — do not reuse the verifier):

> Review `chore/sync-upstream-{target_tag}` against the approved proposal at
> `.claude/sync-upstream/artifacts/proposal-{target_tag}.md`. You have not seen
> the verifier's report; that is intentional.
>
> Look for:
> - Scope creep beyond approved items
> - Backward-compat regressions on older-server paths
> - iOS-vs-Android SSE shape divergence
> - Missing version gates around new endpoints
> - Koin DI wiring gaps
>
> Report clean or a numbered issue list. Do NOT fix — the implementer fixes.

Once the reviewer's pass is clean, spawn a second independent `auditor` for a
final pass. Two independent passes are mandatory — thoroughness is imperative.
Iterate (auditor → implementer fix → re-audit) until clean.

### D4. Present results to user

Once verifier confirms everything passes:

```
## Sync Ready for Device Testing: {current_tag} → {target_tag}

**Branch `chore/sync-upstream-{target_tag}` is local-only. Not pushed. No PR opened.**
This is a first pass. Device testing comes next.

### Files Changed
{grouped by module}

### Test plan (run on device — Android and iOS)
{verifier's user-testable behaviors list, split Android / iOS / v0.8.x-compat}

### How to build and run
1. Android: `./gradlew assembleDebug`, install to device/emulator.
2. iOS: `./gradlew :shared:linkDebugFrameworkIosSimulatorArm64`, open the Xcode project, run.
3. Connect to a v{target_tag} server + at least one older supported server and walk the test plan.

### Version Updates (on this branch, not yet shipped)
- backendTargetVersion (version.properties): {old} → {new}
- UPSTREAM_VERSION tag: {old_tag} → {new_tag}
- Submodule: pinned to {target_tag}
- DISCOVERY.md: {brief summary of what was appended}
- VERSION_GATES.md: {new gate rows added, if any}

### Artifacts
Proposal and phase reports saved under `.claude/sync-upstream/artifacts/`.

### When ready, the user will:
- Report bugs → team lead dispatches follow-up fixes as additional commits on the same local branch.
- Explicitly authorize pushing the branch and opening a PR. Until then, the team does not push.
```

**Do NOT push or open a PR in this phase.** Even if the user says "looks good" in chat, wait for explicit "push it" / "open the PR" before running `git push` or `gh pr create`. Sync PRs are offered to reviewers only after the user has walked the full test plan on real devices.

### D5. Dispatch test runners

After presenting the report, split the Test plan and dispatch one teammate
per section. Each walks the user through their section, captures blockers,
and reports back to team-lead for triage and routing to the implementer.

Section split scales with sync size:
- Small sync (no new feature areas, <10 files): one teammate per platform
  (Android, iOS).
- Medium / large: split by feature area as well (e.g. Auth / Chat / Files /
  Conversations × Android+iOS).

Spawn teammates in parallel (single message, multiple Agent calls).

---

## Error Recovery

| Situation | Recovery |
|-----------|----------|
| Submodule missing | `git submodule add https://github.com/danny-avila/LibreChat.git upstream` |
| `UPSTREAM_VERSION` missing | Create from `version.properties` `backendTargetVersion` + submodule HEAD |
| No newer tags | Report "up to date" and stop |
| Dirty working tree | Ask user to commit/stash |
| Git fetch fails | Show `git remote -v`, check network |
| Android build fails after changes | Verifier messages implementer to fix (both retain context) |
| iOS framework link fails (but Android passes) | Implementer likely forgot iosMain updates — message them to audit iosMain source sets for affected modules |
| Detekt fails (any source set) | Implementer fixes findings. Do not use `--ignore-failures`. |
| User cancels at Phase C | Delete or archive the proposal artifact, tear down the team (`TeamDelete`), stop cleanly |
| Version mismatch (tracked tag ≠ version.properties backendTargetVersion) | Ask user which is the true baseline |
| Session compacted mid-run | List `.claude/sync-upstream/artifacts/` — newest file indicates phase reached; resume from there |
| Teammate unresponsive | Check task status, re-send message; sub-agent as last resort |
