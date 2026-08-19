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

- `BackendVersion.featureSupport(detected, minVersion, landedDate)` — the THREE-state form:
  `PRESENT` / `ABSENT` / `UNKNOWN`. `supportsFeature` is exactly `featureSupport(…) == PRESENT`, so
  it answers "known to have it" and folds two unlike servers into one `false`: one whose build
  commit resolved to a TAG below the threshold (proof it predates the feature) and one that could
  not be placed at all. `ABSENT` is returned only for the first — a tagged `OFFICIAL`/`RC` build
  below the threshold, or a `DEV` build a `landedDate` settles. Everything else that is not
  `PRESENT` is `UNKNOWN`: a null `detected`, and every `DEV` build no `landedDate` settles, because
  a dev build's reported version is a FLOOR and never a ceiling. Reach for this whenever "cannot
  place this server" deserves different handling than "too old" — see the probe-and-latch rule
  below.

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

  **Where support is discoverable, probe once and latch — do not lower the threshold.** The two
  populations `supportsFeature` hides a feature from (dev builds reporting the previous release,
  servers built past the pin) are the two most likely to HAVE it, and no version threshold can
  separate them from a genuinely old server, because the version string they report is the same.
  What can separate them is the route itself: a 404 is the server's own definitive answer. So for
  any gate whose feature can be asked about cheaply, take `featureSupport`, suppress only on
  `ABSENT`, let `UNKNOWN` through to exactly one real call, and cache that call's verdict for the
  rest of the server session. Two things the latch needs: it must be its own field (an `isSupported`
  flag also reads false BEFORE the first probe, so reusing it re-probes on every open), and it must
  be reset on account/server switch, since repositories here are app-wide singletons and the verdict
  belongs to one server. A recurring caller must also re-ask on every tick rather than once at
  start-up — the answer legitimately flips from true to false when the probe lands.

  **Probing is not free everywhere, which is what decides the shape of a gate.** A GET that 404s
  costs one request. A POST under `/api/files` on a pre-0.8.8 server also spends upload-limiter
  quota and a violation score, so it is worth exactly one attempt and never a retry loop. And a
  feature with nothing to probe — an affordance that must be OFFERED before any server has said
  anything, like mid-run steering — has no discovery step at all, so it stays two-state and fails
  closed. Say which of the three a gate is in its catalog row.

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
| `context-projection` POST suppressed (endpoint removed upstream) | `supportsFeature(detected, "0.8.8-rc1")` — a plain version compare since the **v0.8.8-rc1** tag shipped (the `landedDate "2026-06-26"` fallback was dropped at the rc1 sync). A dev build still reporting 0.8.7 issues the POST and its 404 is discarded — the harmless direction. **Deliberately still two-state** (2026-08-18 review): this gate's `false` branch issues a call a 404 makes free, so `UNKNOWN` already gets the behaviour `featureSupport` would give it and splitting the states would change nothing. | POST `/api/endpoints/context-projection` issued to seed the gauge on page load / model-window switch | Call short-circuits to a `null` snapshot (POST skipped — it 404s on the 0.8.8 line); the live `on_context_usage` SSE + `token-config` own the gauge. Inverts the earlier ≥0.8.7-rc1 enable gate. On an unresolved server no call happens at all: `supportsFeature` returns false, but `ChatViewModel.contextGaugeSupported` also requires a non-null version, so `contextUsageEnabled` is off and the delegate never runs. | `core/data/.../repository/EndpointTokenRepositoryImpl.kt` (`getContextProjection`) + `core/network/.../api/EndpointTokenApi.kt` | Endpoint-call code fully removable when min supported server ≥ v0.8.8-rc1 |
| HITL pauses — tool-approval card (approve / reject / edit args / respond) and `ask_user_question` (v0.8.8 line; #13942 landed 2026-06-29, #14139 landed 2026-07-08) | **Deliberately NOT version-gated** — listed here because the obvious gate is wrong. The pause is *self-proving*: `ChatUiState.renderablePendingAction` requires only a non-blank `actionId` and a payload type the card can render, because the action can only be in state if the server pushed `on_pending_action` or reported it on `GET /chat/status` — which is itself proof of both the HITL plumbing and the resume route. A `supportsFeature("0.8.8-rc1", landedDate …)` gate here fails closed on **null** `DetectedBackend`, and null is what any server built past this app's pinned upstream commit resolves to (`BackendCommitMap` only covers commits up to the pin) — i.e. exactly the self-hosted `dev`/`latest` servers that DO pause. Hiding the card there is not a graceful degradation: `StreamingManagerDelegate` keeps `isStreaming = true` for a pause, so the user gets a live cursor forever on a run that will never emit another token, with Stop as the only escape | Nothing to render: a pre-HITL server never emits `on_pending_action`, never reports a `pendingAction` on `/chat/status`, and has no resume route, so `pendingAction` stays null and no card can appear. No client-side gate is needed to produce that outcome | A paused run renders `PendingActionCard` at the tail of the unfinished reply — tool decisions, or the question with its curated options (single- or multi-select), a free-text answer and a Skip that resumes with the declined-answer sentinel. Decisions POST to `/api/agents/chat/resume`; the continuation arrives on the SSE stream already open | `feature/chat/.../viewmodel/ChatUiState.kt` (`renderablePendingAction`) + `feature/chat/.../viewmodel/delegate/PendingActionDelegate.kt` (`submit` re-checks `actionId`) | N/A — nothing to remove |
| Mid-run steering (v0.8.8 line; `POST /api/agents/chat/steer` + `/steer/cancel`, #14220 landed 2026-07-14) | `supportsFeature(detected, "0.8.8-rc1")` — a plain version compare since the **v0.8.8-rc1** tag shipped (the `landedDate "2026-07-14"` fallback was dropped at the rc1 sync). Still fails closed on null `DetectedBackend` — the composer just keeps queueing mid-run. **Deliberately still two-state** (2026-08-18 review): there is nothing to probe. The affordance must be OFFERED before any server has said anything, and a steer that is offered and then 404s mid-run is worse than the queueing it replaced, so `UNKNOWN` keeps the closed answer | Composer keeps its long-standing mid-run behaviour: the send button queues a follow-up and the during-run picker is not rendered at all (one option is not a choice) | Send button offers Steer (default per Settings → Chat → While generating), the picker overrides per message, and accepted steers show as chips above the composer until `on_steer_applied` retires them | `feature/chat/.../viewmodel/ChatViewModel.kt` (`steeringSupported` collector on `configRepository.detectedBackend`) → `FeatureGatesState.steeringSupported` → `ChatUiState.canSteerNow` / `effectiveDuringRunAction` | Fully removable when min supported server ≥ v0.8.8-rc1 |
| Tool favorites (v0.8.8 line; `GET/PUT/DELETE /api/user/settings/favorites/tools`, #13952 landed 2026-07-05) | `featureSupport(detected, "0.8.8-rc1").isRuledOut` — **probe-and-latch** (2026-08-18; was `supportsFeature`, a plain version compare, since the rc1 tag shipped — the `landedDate "2026-07-05"` fallback was dropped then). Suppressed only on `ABSENT`, i.e. a build commit that resolved to a tag below v0.8.8-rc1. **`UNKNOWN` (null `DetectedBackend`, or a dev build still reporting 0.8.7) is probed instead of assumed:** the probe is one GET with no rate limiter and a 404 that means exactly one thing, and the old fail-closed answer cost a working feature — the picker rendered with no star column at all on the self-hosted servers most likely to have the routes. The 404 latches `routeMissingByProbe` for the rest of the server session so the picker does not re-ask on every open, and `clear()` resets it on account switch | No probe is issued and `ToolFavoritesRepository.isSupported` stays false, so the agent editor's tool picker renders without its star column. Everything else in the picker works — selection is agent state, not favorites | The picker shows a per-row star and a Favorites filter chip; pins are per item (`itemType:itemId`) and MCP rows pin the whole server | `core/data/.../repository/ToolFavoritesRepositoryImpl.kt` (`favoritesRuledOut` / `refresh` / `clear`) → `isSupported` → `AgentEditorUiState.areToolFavoritesSupported` | The 404 fallback stays regardless — it is what covers a self-hosted server the commit map cannot classify |
| Queued-attachment TTL touch (`POST /api/files/usage`, v0.8.8 line; #14220, landing commit `9bb351ad9` 2026-07-14) | `featureSupport(detected, "0.8.8-rc1").isRuledOut` — **probe-and-latch** (2026-08-18; was `supportsFeature`, a plain version compare, since the rc1 tag shipped — the `landedDate "2026-07-14"` fallback was dropped then, which left a 0.8.7-reporting dev build skipping the touch). This is the one gate where BOTH directions cost something real, which is why it stopped answering in booleans: calling a server that lacks the route spends upload quota and a violation score on a request that cannot succeed, but withholding the touch from a server that HAS it lets the reaper collect a queued attachment, and the send then references a file the server deleted — one costs a request, the other loses the file. So it suppresses on `ABSENT` (a tag below v0.8.8-rc1, or a latched 404) and touches on `UNKNOWN`. **Exactly one probe:** the first touch on an unplaceable server is it, a 404 latches `usageHoldProbeVerdict = false` and returns `Success` (the touch is best-effort with send-time marking behind it), and a non-404 failure latches nothing — a 500 says nothing about whether the route exists. A 404 on a version-CONFIRMED server does not latch either: that is a proxy or deployment oddity, not evidence about the release. `clear()` resets the verdict on account switch | No touch is issued; a queued attachment relies on send-time marking, exactly as mobile behaved before the route existed. The upload-window reaper can still collect an attachment whose queued message outlives the window | `MessageQueueDelegate.enqueue` touches every queued message's `file_ids`, taking a **renewable bounded hold** on the upload window so a long run or a human-review pause cannot get the attachment reaped before the drain sends it. Originally the touch cleared `expiresAt` outright; #14470 made it a widen-only extension capped at a per-file ceiling (`expiresAt = max(expiresAt, min(now + renewMs, createdAt + maxLifetimeMs))`, `renewMs = 24 h + approvalTtl`), so a client that stops touching lapses one `renewMs` after its last call rather than holding the file forever. Mobile touches at enqueue and then renews on a 30-minute heartbeat over the whole queue, matching upstream's `useQueueDrain`. The heartbeat delays before it renews (never at the top of the loop, so a kill/relaunch cycle cannot burst against a route that now meters per user and scores a breach), awaits each renewal so ticks cannot overlap, and measures the interval with a monotonic clock rather than trusting `delay`'s return. It runs on the `ChatViewModel` scope and is a deliberate no-op once that or the process is gone — the queue is never persisted, so there is nothing left to hold | `core/data/.../repository/FileRepositoryImpl.kt` (`usageHoldRuledOut` / `markFilesUsed` / `clear`) ← `feature/chat/.../viewmodel/delegate/MessageQueueDelegate.kt` (`enqueue`, and `startHoldRenewal`'s per-tick re-ask) ← `shared/.../navigation/NavHostViewModel.kt` (the account-transition `clear()`) | Fully removable when min supported server ≥ v0.8.8-rc1 |
| Shell-script MIME alias routing (v0.8.8-rc1; upstream 5e464bc9) | `isCompatibleOrNewer(serverVersion, "0.8.8-rc1")` inside `normalizeMimeType` — the detected version string is threaded as an optional `serverVersion` parameter through `resolveUploadRoute` / `isTextExtractable` / `isProviderCapable` (null = unknown = older behaviour, per guideline #2) | `application/x-shellscript` is not aliased: it reads as an unknown application-tree type and routes to PROVIDER, the pre-alias behaviour (`text/x-shellscript` keeps its long-standing text/-tree extractability either way) | Both shell-script spellings normalise to `application/x-sh`, so a `.sh` file misreported by Chrome-on-Linux or libmagic routes to text where the provider cannot take it natively | `core/model/.../response/UploadRouting.kt` (`SHELL_SCRIPT_MIME_ALIASES` / `normalizeMimeType`) ← `feature/chat/.../viewmodel/ChatUiState.kt` (`uploadRouteFor`, via `FeatureGatesState.backendVersion`) | v0.8.8-rc1 (drop the gate, keep the aliases) |
| `.potx` picker offer (v0.8.8-rc1; upstream 6c46fd12) | `isCompatibleOrNewer(serverVersion, "0.8.8-rc1")` inside `FileUploadConfig.pickerMimeTypes` (optional `serverVersion` parameter; null = unknown = withheld). Extension→MIME *resolution* (`CommonMimeTypes`, `IosFilePicker.guessMimeType`) is deliberately ungated — a picked `.potx` must carry its real type on every server | The translated picker filter drops the `.potx` type: a pre-rc1 default allowlist rejects the upload, so the file is not offered. The no-restriction (empty ⇒ `*/*`) answer is unaffected either way | `.potx` appears in the translated picker filter wherever the server's allowlist regex admits it | `core/model/.../response/PickerMimeTypes.kt` (`POTX_MIME_TYPE`) ← `feature/chat/.../screen/ChatScreen.kt` + `feature/files/.../viewmodel/FilesViewModel.kt` | v0.8.8-rc1 (drop the gate, keep the type) |
| Quote-to-composer "Add to chat" (v0.8.7; upstream 5eb1c2c1 #13868) | `isCompatibleOrNewer(backendVersion, "0.8.7")` in `ChatUiState.quoteCaptureAvailable`, fails CLOSED on unknown version; also off on assistants endpoints (web `quotesSupported` — they bypass the server-side blockquote merge). Gates only the capture affordance: `message.quotes` DISPLAY stays ungated (self-proving on a received payload) | Selection toolbar carries no "Add to chat" item — a pre-0.8.7 server ignores the request's `quotes` field, so offering capture would silently drop the excerpts | "Add to chat" on the selection toolbar stages excerpt chips above the composer; a fresh send (or composer-origin queue) drains them onto `ChatRequest.quotes`, which the server persists and echoes as `message.quotes`. Regenerate/edit-assistant replay the parent user message's persisted quotes (web `overrideQuotes` parity); continue/edit-user send none; composer steers leave them staged (server steers never carry quotes) | `feature/chat/.../viewmodel/ChatUiState.kt` (`quoteCaptureAvailable`) + `feature/chat/src/androidMain/.../components/AddToChatSelectionMenu.kt` + `feature/chat/.../viewmodel/ChatViewModel.kt` (`takePendingQuotes`) | v0.8.7 (drop the version half of the gate; the assistants guard stays) |

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
- **v0.8.8-line partial sync (untagged dev commit `db431210`, 2026-08-12): ZERO new gates.** Every item in
  that sync is self-proving, permission-driven, or an extra request field older servers ignore, so none of
  them meets the bar in the rule above ("gate only when the client would otherwise call a route the server
  may not have"). Written down because the absence is a decision, not an omission:
  - **Self-proving on a received payload.** The batched `ask_user_question` form renders only when the
    pause the server pushed carries `questions[]`; the phase-label skip keys on `activity_label_type`
    being `"phase"` on a part that arrived; the reconcile handling triggers on a frame that was sent.
    A version gate here could only *disagree* with the payload in hand.
  - **Self-proving on a status code.** The abort/status/send retries branch on a 409/503 plus its code —
    an older server never emits them, so the retry paths are unreachable rather than suppressed.
  - **Correct on every server.** `conversationId: "new"` on abort (the old route skipped `"new"` and took
    the same user-scoped fallback unconditionally) and `normalizeMcpServerName` on MCP tool keys (a name
    of safe characters is returned unchanged, which is every name that ever worked) are not new
    behaviour to gate — they are the spelling that was always right.
  - **Permission-driven, not version-driven.** The shared-link update gate reads SHARED_LINKS CREATE and
    is permissive on unknown, exactly as `canCreateSharedLinks` already was.
  - **Decode surface.** `isShared`, `isEditable`, `adminPanelURL`, the `langfuse*` keys and the five MCP
    reinitialize fields are all absent-means-unknown; `isEditable` additionally fails **closed** here
    (it only narrows the existing per-agent EDIT probe) even though upstream documents fail-open.
  The one item that WOULD have needed a gate — `POST /api/agents/chat/steer/arm`, a genuinely new route —
  is in the deferred P2 set and was not built.
- **v0.8.8-rc1 sync (tag v0.8.8-rc1, 2026-08-14):** the four rc-pending `supportsFeature(...,
  landedDate)` gates (context-projection suppression, mid-run steering, tool-favorites probe,
  queued-attachment TTL touch) were simplified to plain version compares now that the tag ships —
  their catalog rows above carry the live form. THREE new gates added (rows above): the
  shell-script MIME aliases and the `.potx` picker offer at `"0.8.8-rc1"`, and the quote-capture
  affordance at `"0.8.7"` (its landing tag — the feature predates this sync; only the capture UI is
  new). Everything else this sync built is self-proving on a received payload (`activity_end_index`,
  the `{value, annotations}` text form, `PendingAction.expiresAt`, the batched-ask composer flow) or
  an additive request field older servers ignore (`generationCreatedAt` on resume).
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
- **Three-state gating (2026-08-18, post-rc1-sync review):** `BackendVersion.featureSupport` added
  alongside `supportsFeature`, which is now its `== PRESENT` shorthand — every untouched gate keeps
  its behaviour byte for byte (asserted by `supportsFeatureIsExactlyThePresentCase`). The change is
  that a gate can now tell a server it has PLACED below the threshold from one it could not place
  at all, and the two 0.8.8-line gates whose features are discoverable — the tool-favorites probe
  and the queued-attachment TTL touch — moved to probe-and-latch (rows above). This is the fix for
  a real defect the rc1 sync's simplification introduced rather than a new capability: dropping the
  `landedDate` fallbacks left every 0.8.8-cycle dev build (which reports "0.8.7") and every server
  past the commit-map pin classified as too old, and for the TTL touch that meant a queued
  attachment could be reaped mid-run and the send would then reference a deleted file. Restoring
  the dates was the other candidate and was rejected: they rot by construction (day-granularity
  cannot separate same-day commits, and the pin's coverage window re-hides the feature days after
  each sync), whereas the route's own 404 is exact and does not age. The other two 0.8.8 gates
  stayed two-state on purpose, each for a stated reason in its row. `FileRepository.clear()` was
  added and wired into `NavHostViewModel`'s account-transition collector beside the existing
  `toolFavoritesRepository.clear()`, since a probe verdict describes one server and these
  repositories are app-wide singletons.
- **Device verification of the three-state gates (2026-08-18).** `core/data/src/androidInstrumentedTest/
  .../GateProbeDeviceTest.kt` exercises both probe-and-latch gates against a REAL LibreChat over real
  HTTP, on a device, once per server identity. It exists because the unit tests can only pin the
  decision table: they cannot show that a call is actually issued or actually withheld, and the first
  version of this very test passed while its `POST /api/files/usage` never left the device (the test
  client lacked the production `contentType` default, so the request died inside ContentNegotiation).
  It now counts RESPONSES rather than requests, and installs the same `HttpResponseValidator`
  contract as `LibreChatHttpClient` - without that a 404 comes back as an ordinary response, the
  repository reads it as a successful touch, and the latch can never fire.
  Rig: the docker image on `10.0.2.2:3080` with a small logging proxy in front, which (a) records
  every request so "no call was made" is observed rather than inferred and (b) answers 404 for just
  the two gated routes on demand, standing in for a server that predates them. Server identity is
  synthesised with the `BUILD_COMMIT` env var (`packages/api/src/app/build.ts` prefers it over
  `git rev-parse HEAD`), so one running server can impersonate every population the gate must tell
  apart - verified on device: the v0.8.8-rc1 tag resolves `RC/0.8.8-rc1`, the v0.8.7 tag resolves
  `OFFICIAL/0.8.7`, and a commit absent from `BackendCommitMap` resolves to nothing at all. The class
  self-skips when the rig is absent and detects which of the two rigs is up, so neither half can fail
  for want of a fixture.
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

1. Call `BackendVersion.isCompatible(...)`, `BackendVersion.isCompatibleOrNewer(...)`,
   `BackendVersion.supportsFeature(...)` (when dev-commit servers must qualify), or
   `BackendVersion.featureSupport(...)` (when "cannot place this server" must be handled
   separately from "too old") — never parse versions ad hoc. Declare thresholds at the first
   version carrying the feature (usually the line's rc1).
2. Default to **older-server behavior** when the version is unknown (`detectedBackendVersion == null`). The server may not advertise its version; failing open avoids hiding features from self-hosted installs with stripped customFooters. Treat "unknown" as its own case rather than a synonym for "old", and say in the row which way you resolved it: an unplaceable server is usually one built PAST this app's commit-map pin, so assuming it is old is assuming the opposite of what is likely. Where the feature can be discovered by asking, resolve it by probing (rule 6) instead of assuming in either direction.
3. Add a row to the table above. Include file + line anchors and the concrete minimum version at which the gate becomes dead code.
4. If the gated field is a request DTO field, omit it (send `null`) rather than sending a value the server will silently drop — unless you can verify round-trip parity. Silent drops lead to UI state that disagrees with server state.
5. Patch-version gates are supported. Upstream LibreChat regularly ships breaking API and SSE-shape changes inside a patch bump (the same-minor assumption failed moving 0.8.4 → 0.8.5), so use the exact patch the feature shipped in.
6. **If the feature is discoverable, prefer probe-and-latch over a threshold alone.** Gate on
   `featureSupport(...).isRuledOut` so only a proven-old server is suppressed, let `UNKNOWN` make
   exactly one real call, and latch that call's 404 in a dedicated field (not the feature's
   `isSupported` flag, which also reads false before the first probe) that `clear()` resets on
   account/server switch. Re-ask on every tick of any recurring caller — the answer flips once,
   when the probe lands. Skip this and stay two-state only when there is nothing to probe (an
   affordance offered before the server has said anything) or when the `false` branch already
   issues the call anyway; both are legitimate, both belong in the row.
7. **Never infer more from a version string than it carries.** A `DEV` build's reported version is
   a floor, not a ceiling — upstream bumps package.json at rc prep, so a whole release cycle
   reports the previous version. In particular, do not reason that a dev build reporting a version
   two lines below the threshold must be too old: it follows from upstream's release habit, not
   from anything enforced, and one major-bump changes it into a silent feature suppression. Pay the
   probe.
