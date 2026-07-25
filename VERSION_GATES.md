# Version Gates

Switchboard supports a range of LibreChat backend server versions. This file catalogs every
place in the codebase where behavior branches based on the detected server version so
the compatibility surface is auditable. When the minimum supported server version is
raised, entries here can be simplified or removed.

The canonical API for version comparisons lives in
`core/common/src/commonMain/kotlin/com/garfiec/librechat/core/common/BackendVersion.kt`:

- `BackendVersion.parse(version)` — parse a loose semver string (`"v0.8.5"`, `"0.8"`, `"0.8.8-rc1"`, …).
  The prerelease suffix is retained and ordered (`0.8.8-rc1 < 0.8.8-rc2 < 0.8.8`); build
  metadata (`+dev.<sha>` on partial-sync targets) is stripped.
- `BackendVersion.isCompatible(supported, actual)` — same release line (`major.minor.patch`,
  prerelease ignored: rc and final of one line are mutually compatible). Feeds the soft
  mismatch banner.
- `BackendVersion.isCompatibleOrNewer(actual, minimum)` — `actual ≥ minimum` by full semver
  order including prerelease. Declare gates at the FIRST version carrying the feature — for a
  feature present in rc1, that is `"0.8.8-rc1"`, not `"0.8.8"` (which would exclude rc servers).
- `BackendVersion.supportsFeature(detected, minVersion, landedDate)` — gate that also
  recognizes servers built from UNTAGGED upstream dev commits. Upstream bumps package.json only
  at rc prep, so a dev build carrying next-release features still reports the previous release;
  this helper falls back to comparing the server build commit's date (from `BackendCommitMap`)
  against the ISO **UTC** date the feature landed upstream
  (`TZ=UTC git log -1 --date=format-local:%Y-%m-%d --format=%cd <landing-commit>` — NOT `%cs`,
  which renders per-committer timezones and is non-monotonic on upstream's history). Use for
  features synced ahead of any tag (partial syncs). Fails CLOSED on null `detected` — note the
  commit map only covers commits up to the app's pinned submodule commit, so a server built
  from a LATER commit resolves to null and hides date-gated features until the next
  sync/regeneration (accepted fail-safe tradeoff).

  **Dates are monotonic but only day-granular.** The map stores one date per commit, so a
  date gate cannot separate commits that landed on the SAME day as the feature — the dozen-plus
  commits upstream merges before the landing one that day all satisfy `commitDate >= landedDate`.
  Choose the landedDate whose misclassification is harmless rather than the literally-correct one:
  the landing day when treating a same-day PREDECESSOR as having the feature is tolerable, the day
  AFTER when it is not (which instead treats same-day SUCCESSORS as lacking it). State which
  direction was chosen, and why it is the safe one, in the gate's catalog row. Getting both edges
  right would require a per-commit ordinal in `BackendCommitMap`, which it does not have.

The detected server version is exposed via `ConfigRepository.detectedBackendVersion`
(plain string) and `ConfigRepository.detectedBackend` (rich `DetectedBackend`: version +
build classification OFFICIAL/RC/DEV/UNKNOWN + build-commit date — what `supportsFeature`
consumes), both populated once `checkBackendVersion()` runs on app startup / server-switch.

`BackendVersion.SUPPORTED_BACKEND_VERSION` (the backend this build targets) is **generated**
from `backendTargetVersion` in the root `version.properties` by core/common's
`generateBackendVersion` Gradle task — bump that property, not the constant.

## Catalog

| Feature | Gated since | Behavior on older | Behavior on newer | File:line | Safe to remove when min supported server ≥ |
|---|---|---|---|---|---|
| `isCollaborative` agent toggle | v0.8.5 (2026-04-23) | Toggle visible; mobile sends `isCollaborative` + `projectIds` to server | Toggle hidden; inline hint "Access permissions are managed server-side in this version" rendered instead; fields not sent | `feature/agents/.../components/AgentSharingSection.kt` + `feature/agents/.../viewmodel/AgentEditorViewModel.kt` (`observeServerVersion`, `save`) | v0.8.5 |
| `xhigh` reasoning-effort dropdown value | v0.8.5 (2026-04-25) | `xhigh` filtered out of `reasoning_effort` and `effort` dropdowns (older Anthropic/Bedrock/OpenAI schemas reject the unknown enum) | `xhigh` shown alongside `low/medium/high/max` | `core/ui/.../components/EndpointParameterRegistry.kt` (`getDefinitions(xhighEffortSupported)`) + `feature/chat/.../viewmodel/ChatViewModel.kt` (xhigh observer in `init`) | v0.8.5 |
| Pin/unpin conversation action | v0.8.7 (2026-06-26) | Pin action hidden (older servers lack `POST /api/convos/pin` → would 404) | Pin/unpin in the drawer long-press menu + a Pinned section atop the drawer | `shared/.../NavHostViewModel.kt` (`drawerActionMenuState` → `pinEnabled`) + `shared/.../DrawerContent.kt` | v0.8.7 |
| Move-to-project action (Chat Projects) | v0.8.7 (2026-06-26) | Move-to-project action hidden (older servers lack `/api/projects`) | Move-to-project picker in the drawer long-press menu (create/assign/unassign) | `shared/.../NavHostViewModel.kt` (`drawerActionMenuState` → `projectsEnabled`) + `shared/.../DrawerContent.kt` | v0.8.7 |
| Chat Projects browse UI (folder section + index/detail) | v0.8.7 (2026-06-26) | Drawer Projects folder section hidden (older servers lack `/api/projects`) | Expandable drawer folder section + `Projects` index + `ProjectChats` detail screens (inline chats / Show all / CRUD) | `shared/.../NavHostViewModel.kt` (`projectsSection` + the version-gated `loadProjects` init collector → only fires ≥0.8.7) + `shared/.../DrawerContent.kt` (`uiState.projectsEnabled`) | v0.8.7 |
| Context-usage gauge | v0.8.7 (2026-06-26) | Gauge hidden (no `on_context_usage` SSE / `token-config` / `context-projection` on older servers) | Slim context gauge below the chat app bar when `interface.contextUsage` is on | `feature/chat/.../viewmodel/ChatViewModel.kt` (`contextGaugeSupported` in the role+interface combine → `contextUsageEnabled`/`contextCostEnabled`) | v0.8.7 |
| `context-projection` POST suppressed (endpoint removed upstream) | `supportsFeature("0.8.8-rc1", landedDate "2026-06-26")` — landing commit `376370d6` (#13953) landed **2026-06-25**; the gate deliberately uses the NEXT day (see the day-granularity caveat above): three commits merged earlier that same day would otherwise be read as post-removal and lose their gauge seed, whereas the rounding-up error only makes a same-day post-removal build issue one 404 that yields null. | POST `/api/endpoints/context-projection` issued to seed the gauge on page load / model-window switch | Call short-circuits to a `null` snapshot (POST skipped — it 404s on the 0.8.8 line); the live `on_context_usage` SSE + `token-config` own the gauge. Inverts the earlier ≥0.8.7 enable gate. On an unresolved server no call happens at all: `supportsFeature` returns false, but `ChatViewModel.contextGaugeSupported` also requires a non-null version, so `contextUsageEnabled` is off and the delegate never runs. | `core/data/.../repository/EndpointTokenRepositoryImpl.kt` (`getContextProjection`) + `core/network/.../api/EndpointTokenApi.kt` | Once **v0.8.8-rc1** ships: drop `landedDate`, gate becomes plain `isCompatibleOrNewer(version, "0.8.8-rc1")`; endpoint-call code fully removable when min supported server ≥ v0.8.8-rc1 |
| Tool-approval HITL card (approve / reject / edit args / respond) | `supportsFeature("0.8.8-rc1", landedDate "2026-06-29")` — landing commit `6dbf9d5ad` (#13942), which shipped both `POST /api/agents/chat/resume` and the tool-approval pause together | No approval controls. Correct, not a degradation: such a server never emits `on_pending_action`, never reports a `pendingAction` on `/chat/status`, and has no resume route to accept a decision — a card there could only 404 | A paused run renders `PendingActionCard` at the tail of the unfinished reply; decisions POST to `/api/agents/chat/resume` and the continuation arrives on the SSE stream already open | `feature/chat/.../viewmodel/ChatViewModel.kt` (`toolApprovalSupported` in the role+interface combine → `gates.toolApprovalEnabled`), filtered at render by `ChatUiState.renderablePendingAction` | Once **v0.8.8-rc1** ships: drop `landedDate`, gate becomes plain `isCompatibleOrNewer(version, "0.8.8-rc1")` |
| `ask_user_question` clarification pause | `supportsFeature("0.8.8-rc1", landedDate "2026-07-08")` — landing commit `988a14a40` (#14139). Deliberately LATER than the tool-approval row: the ask tool shipped ~2 weeks after the approval plumbing it reuses, so a server in between can pause for approval yet know nothing of `ask_user_question` | Question card hidden (same reasoning as the row above) | The question renders with its curated options (single- or multi-select) plus a free-text answer and a Skip that resumes with the declined-answer sentinel | Same files as the row above (`askUserQuestionSupported` → `gates.askUserQuestionEnabled`) | Once **v0.8.8-rc1** ships: drop `landedDate` |

## Sync notes

- **v0.8.6 (2026-06-01):** no NEW runtime version gates added. The headline upstream feature
  (Skills + Subagents) is deferred wholesale, so there is no mobile code path branching on
  `isCompatibleOrNewer(version, "0.8.6")` yet. The sync was a version bump (`backendTargetVersion`
  → 0.8.6) plus additive forward-compat data fields (agent `skills`/`skills_enabled`/`subagents`,
  config `skills`/`buildInfo`/`rum`/`cloudFront`/`autoSubmitFromUrl`/`retentionMode`) that parse
  but gate nothing. When a Skills/Subagents UI is eventually built, gate it at
  `isCompatibleOrNewer(version, "0.8.6")` and add a row above.
- **v0.8.7 (2026-06-26):** four gated surfaces added — pin, move-to-project, the Chat Projects
  browse UI (drawer folder section + `Projects` index + `ProjectChats` detail), and the context
  gauge (now also seeded on chat open / model switch via `context-projection`) — all at
  `isCompatibleOrNewer(version, "0.8.7")`. These deliberately **fail CLOSED on unknown version**
  (`version == null` hides the feature), a divergence from guideline #2's "default to older-server
  behavior" — for these, older-server behavior *is* "feature absent", and surfacing an action that
  would `404` (pin/projects) or has no data source (gauge) is worse than hiding it. The additive
  parse-only fields from this sync (`promptCacheTtl`, `pinned`, `chatProjectId`, and the new
  `interface` keys `contextUsage`/`contextCost`/`titleTiming`/`defaultPinnedTools`/`sharedLinks`/
  `maxCatalogSkills`) gate nothing on their own. The immediate-title SSE (`event:'title'`) is **not**
  version-gated — it's purely additive and absent servers simply never emit it. The chat-payload
  `timezone` field (#13815) is likewise ungated: always sent (IANA id from
  `TimeZone.currentSystemDefault()`); older servers ignore the unknown key.
- **v0.8.7 known-deferred parity gaps (not built, tracked):** `url_context` conversation toggle (M2)
  and per-message `quotes[]` round-trip (M3) — both additive, low priority; see
  `proposal-v0.8.7.md` Deferred Items.
- **v0.8.8-line partial sync (untagged dev commit `6c97a7f4`, 2026-07-24):** three NEW version gates — the
  `context-projection` POST suppression and the two human-in-the-loop pause surfaces (rows above). All three
  are `supportsFeature` **date gates** for the same reason. The target is untagged: upstream removed `POST /api/endpoints/context-projection` in #13953 (landing commit
  `376370d6`, UTC committer date **2026-06-25**), but package.json on the target commit still reports 0.8.7,
  so a plain version compare can't distinguish a pre- from a post-removal 0.8.7 dev server — the build
  commit's date does. The gate is declared at **2026-06-26**, one day past the landing, because three
  commits (`5c5ef37e3` #13940, `03ecac8ac` #13947, `e26ce4713` #13954) merged earlier on 2026-06-25 and a
  day-granular gate cannot exclude them; erring the other way costs at most one 404. Drop the
  `landedDate` and switch to plain `isCompatibleOrNewer(version, "0.8.8-rc1")`
  once the **v0.8.8-rc1** tag ships. Everything else this sync brought is **ungated / additive** and gates
  nothing on its own: the chat-payload `clientRequestId` idempotency key (#14344 — always sent, older servers
  ignore it), the `steer` message content-part (#14220 — parse-only forward-compat, `ContentType.STEER` +
  nullable `MessageContentPart.steer`), and the reworked `DELETE /api/files` `tool_resource` contract (#14149 —
  mobile already compliant, no branch). The `ALLOW_EMAIL_LOGIN` login gate (#14180) is **config-driven, not
  version-gated**: it keys on `StartupConfig.emailLoginEnabled` from `/api/config` (fail-open to enabled) plus a
  403 fallback on `POST /api/auth/login` — no `BackendVersion` call.
- **Prerelease parse fix:** `BackendVersion.parse()` now strips semver prerelease (`-rc1`) and
  build-metadata (`+build`) suffixes before splitting. This affects ALL existing gates: previously a
  prerelease server footer (e.g. `0.8.6-rc1`) parsed as `0.8.0`, which would have **falsely failed**
  every `isCompatibleOrNewer` check and hidden 0.8.5+ features. After the fix, `0.8.6-rc1` correctly
  evaluates as `0.8.6`, so the `isCollaborative` and `xhigh` gates above now behave correctly against
  prerelease servers. No gate threshold changed — only the version-string parsing feeding them.
- **Prerelease-aware ordering (2026-07-24):** `parse()` now RETAINS the prerelease suffix and
  `isCompatibleOrNewer` orders it (`rc1 < rc2 < final`), enabling rc-granularity gates for rc/partial
  syncs. Because a bare `"0.8.7"` threshold now *excludes* `0.8.7-rc*` servers (the old
  strip-and-compare treated them as equal), each pre-existing gate threshold was re-verified against
  the upstream tags and set to the FIRST version actually carrying its feature:
  - Relaxed to rc1 (feature present in the rc): `isCollaborative` + `xhigh` → `"0.8.5-rc1"`;
    move-to-project, Projects browse UI, and ShareRepository's modern-shape check → `"0.8.7-rc1"`.
  - Kept at the final (feature landed BETWEEN rc1 and final — the old strip-based gates wrongly
    enabled these on rc servers, now fixed): pin (`POST /api/convos/pin`, upstream 743f57f63)
    and the context gauge (`/api/endpoints/context-projection`, upstream fdc7e64bb) → `"0.8.7"`.
- **Partial-sync gates:** features synced from UNTAGGED upstream commits gate via
  `supportsFeature(detected, minVersion, landedDate)` where `minVersion` is the upcoming rc line
  (e.g. `"0.8.8-rc1"` before that tag exists) and `landedDate` is the ISO committer date of the
  upstream commit that landed the feature. Record the landedDate in the gate's catalog row so the
  date can be dropped once the rc/final tag ships and plain version gating suffices. Before
  recording it, check the landing commit's same-day neighbours (`TZ=UTC git log --format='%cd %h %s'
  --date=format-local:%Y-%m-%d` around it) and apply the day-granularity rule above — the literal
  landing date is the right choice only when a same-day predecessor being treated as post-landing is
  harmless.

## Guidelines for adding a new gate

1. Call `BackendVersion.isCompatible(...)`, `BackendVersion.isCompatibleOrNewer(...)`, or
   `BackendVersion.supportsFeature(...)` (when dev-commit servers must qualify) — never parse
   versions ad hoc. Declare thresholds at the first version carrying the feature (usually the
   line's rc1).
2. Default to **older-server behavior** when the version is unknown (`detectedBackendVersion == null`). The server may not advertise its version; failing open avoids hiding features from self-hosted installs with stripped customFooters.
3. Add a row to the table above. Include file + line anchors and the concrete minimum version at which the gate becomes dead code.
4. If the gated field is a request DTO field, omit it (send `null`) rather than sending a value the server will silently drop — unless you can verify round-trip parity. Silent drops lead to UI state that disagrees with server state.
5. Patch-version gates are supported. Upstream LibreChat regularly ships breaking API and SSE-shape changes inside a patch bump (the same-minor assumption failed moving 0.8.4 → 0.8.5), so use the exact patch the feature shipped in.
