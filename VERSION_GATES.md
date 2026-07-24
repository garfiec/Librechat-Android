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
  against the ISO date the feature landed upstream (`git log --format=%cs` of the landing
  commit). Use for features synced ahead of any tag (partial syncs). Fails CLOSED on null
  `detected`.

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
- **Prerelease parse fix:** `BackendVersion.parse()` now strips semver prerelease (`-rc1`) and
  build-metadata (`+build`) suffixes before splitting. This affects ALL existing gates: previously a
  prerelease server footer (e.g. `0.8.6-rc1`) parsed as `0.8.0`, which would have **falsely failed**
  every `isCompatibleOrNewer` check and hidden 0.8.5+ features. After the fix, `0.8.6-rc1` correctly
  evaluates as `0.8.6`, so the `isCollaborative` and `xhigh` gates above now behave correctly against
  prerelease servers. No gate threshold changed — only the version-string parsing feeding them.
- **Prerelease-aware ordering (2026-07-24):** `parse()` now RETAINS the prerelease suffix and
  `isCompatibleOrNewer` orders it (`rc1 < rc2 < final`), enabling rc-granularity gates for rc/partial
  syncs. Because a bare `"0.8.7"` threshold now *excludes* `0.8.7-rc*` servers (the old
  strip-and-compare treated them as equal), every pre-existing gate threshold was relaxed to the rc1
  of its line (`"0.8.5"` → `"0.8.5-rc1"`, `"0.8.7"` → `"0.8.7-rc1"`) — the gated features all
  shipped before their line's rc1 was cut, so this preserves the previously-observed behavior for rc
  servers exactly. The catalog's "Gated since" column reads as "the release line", with the actual
  call-site threshold at that line's rc1.
- **Partial-sync gates:** features synced from UNTAGGED upstream commits gate via
  `supportsFeature(detected, minVersion, landedDate)` where `minVersion` is the upcoming rc line
  (e.g. `"0.8.8-rc1"` before that tag exists) and `landedDate` is the ISO committer date of the
  upstream commit that landed the feature. Record the landedDate in the gate's catalog row so the
  date can be dropped once the rc/final tag ships and plain version gating suffices.

## Guidelines for adding a new gate

1. Call `BackendVersion.isCompatible(...)`, `BackendVersion.isCompatibleOrNewer(...)`, or
   `BackendVersion.supportsFeature(...)` (when dev-commit servers must qualify) — never parse
   versions ad hoc. Declare thresholds at the first version carrying the feature (usually the
   line's rc1).
2. Default to **older-server behavior** when the version is unknown (`detectedBackendVersion == null`). The server may not advertise its version; failing open avoids hiding features from self-hosted installs with stripped customFooters.
3. Add a row to the table above. Include file + line anchors and the concrete minimum version at which the gate becomes dead code.
4. If the gated field is a request DTO field, omit it (send `null`) rather than sending a value the server will silently drop — unless you can verify round-trip parity. Silent drops lead to UI state that disagrees with server state.
5. Patch-version gates are supported. Upstream LibreChat regularly ships breaking API and SSE-shape changes inside a patch bump (the same-minor assumption failed moving 0.8.4 → 0.8.5), so use the exact patch the feature shipped in.
