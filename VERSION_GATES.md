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
  sync/regeneration.

  **That null case is the gate's real failure mode, not an edge case.** A self-hosted server
  tracking upstream `dev`/`latest` drifts past the pin within days, so the population most likely
  to HAVE a just-landed feature is the population that resolves to null. Gate on `supportsFeature`
  only when the client would otherwise call a route the server may not have *without being asked
  to* — suppressing such a call is a genuine fail-safe. Do NOT gate on it when the server has
  already announced the capability (an SSE frame, a status field, a `/api/config` flag): the
  announcement is self-proving, it arrives from servers the commit map cannot classify, and gating
  on top of it suppresses the feature precisely where it works. Say which of the two a gate is in
  its catalog row, and check the null case explicitly — "behavior on older" is not the same
  question as "behavior on unrecognized".

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
| `isCollaborative` agent toggle | v0.8.5-rc1 (2026-04-09) | Toggle visible; mobile sends `isCollaborative` + `projectIds` to server | Toggle hidden; inline hint "Access permissions are managed server-side in this version" rendered instead; fields not sent | `feature/agents/.../components/AgentSharingSection.kt` + `feature/agents/.../viewmodel/delegate/AgentCapabilitiesDelegate.kt` (`observeServerVersion`) | v0.8.5-rc1 |
| `xhigh` reasoning-effort dropdown value | v0.8.5-rc1 (2026-04-09) | `xhigh` filtered out of `reasoning_effort` and `effort` dropdowns (older Anthropic/Bedrock/OpenAI schemas reject the unknown enum) | `xhigh` shown alongside `low/medium/high/max` | `core/ui/.../components/EndpointParameterRegistry.kt` (`getDefinitions(xhighEffortSupported)`) + `feature/chat/.../viewmodel/ChatViewModel.kt` (xhigh observer in `init`) | v0.8.5-rc1 |
| Pin/unpin conversation action | v0.8.7 (2026-06-26) | Pin action hidden (older servers lack `POST /api/convos/pin` → would 404) | Pin/unpin in the drawer long-press menu + a Pinned section atop the drawer | `feature/conversations/.../drawer/DrawerViewModel.kt` (`drawerActionMenuState` → `pinSupported`) + `feature/conversations/.../drawer/DrawerContent.kt` | v0.8.7 |
| Move-to-project action (Chat Projects) | v0.8.7-rc1 (2026-06-15) | Move-to-project action hidden (older servers lack `/api/projects`) | Move-to-project picker in the drawer long-press menu (create/assign/unassign) | `feature/conversations/.../drawer/DrawerViewModel.kt` (`drawerActionMenuState` → `projectsSupported`) + `feature/conversations/.../drawer/DrawerContent.kt` | v0.8.7-rc1 |
| Chat Projects browse UI (folder section + index/detail) | v0.8.7-rc1 (2026-06-15) | Drawer Projects folder section hidden (older servers lack `/api/projects`) | Expandable drawer folder section + `Projects` index + `ProjectChats` detail screens (inline chats / Show all / CRUD) | `feature/conversations/.../drawer/DrawerViewModel.kt` (`projectsSection` + the version-gated `loadProjects` init collector → only fires ≥0.8.7-rc1) + `feature/conversations/.../drawer/DrawerContent.kt` (`uiState.projectsEnabled`) | v0.8.7-rc1 |
| Context-usage gauge | v0.8.7-rc1 (2026-06-15) | Gauge hidden (no `on_context_usage` SSE / `token-config` on older servers) | Slim context gauge below the chat app bar when `interface.contextUsage` is on. rc1 servers additionally 404 the optional `context-projection` seed (landed rc1 → final, upstream fdc7e64bb); the delegate discards the failed projection and the live SSE owns the gauge | `feature/chat/.../viewmodel/ChatViewModel.kt` (`contextGaugeSupported` in the role+interface combine → `contextUsageEnabled`) | v0.8.7-rc1 |
| `context-projection` POST suppressed (endpoint removed upstream) | `supportsFeature("0.8.8-rc1", landedDate "2026-06-26")` — landing commit `376370d6` (#13953) landed **2026-06-25**; the gate deliberately uses the NEXT day (see the day-granularity caveat above): three commits merged earlier that same day would otherwise be read as post-removal and lose their gauge seed, whereas the rounding-up error only makes a same-day post-removal build issue one 404 that yields null. | POST `/api/endpoints/context-projection` issued to seed the gauge on page load / model-window switch | Call short-circuits to a `null` snapshot (POST skipped — it 404s on the 0.8.8 line); the live `on_context_usage` SSE + `token-config` own the gauge. Inverts the earlier ≥0.8.7-rc1 enable gate. On an unresolved server no call happens at all: `supportsFeature` returns false, but `ChatViewModel.contextGaugeSupported` also requires a non-null version, so `contextUsageEnabled` is off and the delegate never runs. | `core/data/.../repository/EndpointTokenRepositoryImpl.kt` (`getContextProjection`) + `core/network/.../api/EndpointTokenApi.kt` | Once **v0.8.8-rc1** ships: drop `landedDate`, gate becomes plain `isCompatibleOrNewer(version, "0.8.8-rc1")`; endpoint-call code fully removable when min supported server ≥ v0.8.8-rc1 |
| HITL pauses — tool-approval card (approve / reject / edit args / respond) and `ask_user_question` (v0.8.8 line; #13942 landed 2026-06-29, #14139 landed 2026-07-08) | **Deliberately NOT version-gated** — listed here because the obvious gate is wrong. The pause is *self-proving*: `ChatUiState.renderablePendingAction` requires only a non-blank `actionId` and a payload type the card can render, because the action can only be in state if the server pushed `on_pending_action` or reported it on `GET /chat/status` — which is itself proof of both the HITL plumbing and the resume route. A `supportsFeature("0.8.8-rc1", landedDate …)` gate here fails closed on **null** `DetectedBackend`, and null is what any server built past this app's pinned upstream commit resolves to (`BackendCommitMap` only covers commits up to the pin) — i.e. exactly the self-hosted `dev`/`latest` servers that DO pause. Hiding the card there is not a graceful degradation: `StreamingManagerDelegate` keeps `isStreaming = true` for a pause, so the user gets a live cursor forever on a run that will never emit another token, with Stop as the only escape | Nothing to render: a pre-HITL server never emits `on_pending_action`, never reports a `pendingAction` on `/chat/status`, and has no resume route, so `pendingAction` stays null and no card can appear. No client-side gate is needed to produce that outcome | A paused run renders `PendingActionCard` at the tail of the unfinished reply — tool decisions, or the question with its curated options (single- or multi-select), a free-text answer and a Skip that resumes with the declined-answer sentinel. Decisions POST to `/api/agents/chat/resume`; the continuation arrives on the SSE stream already open | `feature/chat/.../viewmodel/ChatUiState.kt` (`renderablePendingAction`) + `feature/chat/.../viewmodel/delegate/PendingActionDelegate.kt` (`submit` re-checks `actionId`) | N/A — nothing to remove |
| Mid-run steering (v0.8.8 line; `POST /api/agents/chat/steer` + `/steer/cancel`, #14220 landed 2026-07-14) | `supportsFeature("0.8.8-rc1", landedDate "2026-07-14")` — the landing day itself, not the day after: a same-day predecessor being read as "has steering" costs one rejected POST whose text is re-homed to the queue, whereas erring the other way would hide the feature from real 0.8.8 servers for a day's worth of commits | Composer keeps its long-standing mid-run behaviour: the send button queues a follow-up and the during-run picker is not rendered at all (one option is not a choice) | Send button offers Steer (default per Settings → Chat → While generating), the picker overrides per message, and accepted steers show as chips above the composer until `on_steer_applied` retires them | `feature/chat/.../viewmodel/ChatViewModel.kt` (`steeringSupported` collector on `configRepository.detectedBackend`) → `FeatureGatesState.steeringSupported` → `ChatUiState.canSteerNow` / `effectiveDuringRunAction` | Once **v0.8.8-rc1** ships: drop `landedDate`. Fully removable when min supported server ≥ v0.8.8-rc1 |
| Tool favorites (v0.8.8 line; `GET/PUT/DELETE /api/user/settings/favorites/tools`, #13952 landed 2026-07-05) | `supportsFeature("0.8.8-rc1", landedDate "2026-07-05")` — the landing day itself. A same-day predecessor misread as having the routes costs one GET that 404s, and the 404 handler turns that into "unsupported" anyway; rounding up would instead hide the feature from a day's worth of real 0.8.8 servers. This is a *suppress-a-call-the-server-may-not-have* gate, not a suppress-an-announced-capability one, so it is the legitimate kind. **Null `DetectedBackend`:** fails closed — no probe, no stars. A server past the pin therefore shows no pin affordance until the next sync; accepted, because the cost is a missing star rather than a stuck UI | No probe is issued and `ToolFavoritesRepository.isSupported` stays false, so the agent editor's tool picker renders without its star column. Everything else in the picker works — selection is agent state, not favorites | The picker shows a per-row star and a Favorites filter chip; pins are per item (`itemType:itemId`) and MCP rows pin the whole server | `core/data/.../repository/ToolFavoritesRepositoryImpl.kt` (`refresh`) → `isSupported` → `AgentEditorUiState.areToolFavoritesSupported` | Once **v0.8.8-rc1** ships: drop `landedDate`. The 404 fallback stays regardless — it is what covers a self-hosted server the commit map cannot classify |
| Queued-attachment TTL touch (`POST /api/files/usage`, v0.8.8 line; #14220, landing commit `9bb351ad9` 2026-07-14) | `supportsFeature("0.8.8-rc1", landedDate "2026-07-14")` — the landing day itself, the same date as the steering gate because it is the same landing commit. A same-day predecessor misread as having the route pays one 404 per queued-with-attachments message, and that 404 is *not* free: pre-0.8.8 servers run `fileUploadIpLimiter` + `fileUploadUserLimiter` on every POST under `/api/files` except `/speech` (the `/usage` exemption arrived with the route), so it spends upload quota and a violation score. Rounding up to the next day instead costs real 0.8.8 servers a day's worth of TTL pushes, which is the direction that actually loses an attachment. This is a *suppress-a-call-the-server-may-not-have* gate — the legitimate kind. **Null `DetectedBackend`:** fails closed, no touch; a server past the pin falls back to send-time marking until the next sync | No touch is issued; a queued attachment relies on send-time marking, exactly as mobile behaved before the route existed. The upload-window reaper can still collect an attachment whose queued message outlives the window | `MessageQueueDelegate.enqueue` touches every queued message's `file_ids`, taking a **renewable bounded hold** on the upload window so a long run or a human-review pause cannot get the attachment reaped before the drain sends it. Originally the touch cleared `expiresAt` outright; #14470 made it a widen-only extension capped at a per-file ceiling (`expiresAt = max(expiresAt, min(now + renewMs, createdAt + maxLifetimeMs))`, `renewMs = 24 h + approvalTtl`), so a client that stops touching lapses one `renewMs` after its last call rather than holding the file forever. Mobile touches at enqueue and then renews on a 30-minute heartbeat over the whole queue, matching upstream's `useQueueDrain`. The heartbeat delays before it renews (never at the top of the loop, so a kill/relaunch cycle cannot burst against a route that now meters per user and scores a breach), awaits each renewal so ticks cannot overlap, and measures the interval with a monotonic clock rather than trusting `delay`'s return. It runs on the `ChatViewModel` scope and is a deliberate no-op once that or the process is gone — the queue is never persisted, so there is nothing left to hold | `core/data/.../repository/FileRepositoryImpl.kt` (`markFilesUsed`) ← `feature/chat/.../viewmodel/delegate/MessageQueueDelegate.kt` (`enqueue`) | Once **v0.8.8-rc1** ships: drop `landedDate`. Fully removable when min supported server ≥ v0.8.8-rc1 |

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
  `isCompatibleOrNewer(version, "0.8.7")` at the time (three of the four were later relaxed to
  `"0.8.7-rc1"` — see the 0.8.8-line note below and the catalog, which carry the live thresholds).
  These deliberately **fail CLOSED on unknown version**
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
- **v0.8.8-line partial sync (untagged dev commit `6c97a7f4`, 2026-07-23):** four NEW `supportsFeature` date
  gates — the `context-projection` POST suppression, mid-run steering, the tool-favorites probe, and the
  queued-attachment TTL touch — plus one
  deliberate NON-gate, the human-in-the-loop pause surfaces (rows above). All four are date gates for the same reason. The target is untagged: upstream removed `POST /api/endpoints/context-projection` in #13953 (landing commit
  `376370d6`, UTC committer date **2026-06-25**), but package.json on the target commit still reports 0.8.7,
  so a plain version compare can't distinguish a pre- from a post-removal 0.8.7 dev server — the build
  commit's date does. The gate is declared at **2026-06-26**, one day past the landing, because three
  commits (`5c5ef37e3` #13940, `03ecac8ac` #13947, `e26ce4713` #13954) merged earlier on 2026-06-25 and a
  day-granular gate cannot exclude them; erring the other way costs at most one 404. Drop the
  `landedDate` and switch to plain `isCompatibleOrNewer(version, "0.8.8-rc1")`
  once the **v0.8.8-rc1** tag ships.
- **Why steering is gated and HITL pauses are not** — the two 0.8.8 rows look contradictory and are not. A
  pause is *received*: it can only be in state because the server pushed it, which is itself proof of the
  feature, and hiding the card would strand the user on a live cursor that never advances. Steering must be
  *offered* before any server has said anything about it, and failing closed costs nothing — the composer
  simply keeps queueing mid-run, which is what mobile did before steering existed and what every supported
  server handles. The coverage-window consequence is real and accepted: a server built past this app's pinned
  upstream commit resolves to a null `DetectedBackend` and hides steering until the next sync.
- **Steering never gates the user's words, only the affordance.** A steer that is offered and then refused —
  wrong gate answer, run already ended, run paused, queue full, route missing — is re-homed into the
  follow-up queue (or sent as a new turn when the run is provably over), so a wrong gate answer in the
  permissive direction degrades to today's behaviour rather than losing a message. Everything else this sync brought is **ungated / additive** and gates
  nothing on its own: the chat-payload `clientRequestId` idempotency key (#14344 — always sent, older servers
  ignore it), the `steer` message content-part (#14220 — parse-only forward-compat, `ContentType.STEER` +
  nullable `MessageContentPart.steer`), and the reworked `DELETE /api/files` `tool_resource` contract (#14149 —
  mobile already compliant, no branch). The `ALLOW_EMAIL_LOGIN` login gate (#14180) is **config-driven, not
  version-gated**: it keys on `StartupConfig.emailLoginEnabled` from `/api/config` (fail-open to enabled) plus a
  403 fallback on `POST /api/auth/login` — no `BackendVersion` call. The composer **memory toggle** (#13869) is
  likewise config/permission-driven: MEMORIES USE+CREATE+UPDATE AND the agents endpoint's `memory` capability
  AND the user's own `personalization.memories` opt-out. It fails CLOSED (unlike its sibling tool gates)
  because the capability is off by default server-side, so assuming it would offer a toggle whose
  `ephemeralAgent.memory` flag the server silently drops.
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
    move-to-project, Projects browse UI, ShareRepository's modern-shape check, and the context
    gauge → `"0.8.7-rc1"`. The gauge's own data sources (`on_context_usage` SSE +
    `/api/endpoints/token-config`) are both in rc1; only the optional `context-projection` seed
    (upstream fdc7e64bb) landed later, and a 404 there is already discarded by
    `ContextProjectionDelegate`, so gating the whole gauge on the final would hide a working
    feature on rc servers.
  - Kept at the final (feature landed BETWEEN rc1 and final — the old strip-based gate wrongly
    enabled it on rc servers, now fixed): pin (`POST /api/convos/pin`, upstream 743f57f63) →
    `"0.8.7"`.
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
