# Switchboard - Discovery & Context Document

## Project Goal
Build a native mobile app (Switchboard) with full feature parity to the LibreChat web application. The app must connect to an existing LibreChat backend server (no backend changes). Users specify the server URL during onboarding.

## Tech Stack (Mandated)
- **UI**: Jetpack Compose + Compose Navigation
- **DI**: Koin
- **Network**: Ktor Client
- **Serialization**: Kotlinx Serialization
- **Min SDK**: TBD (recommend 26+)

---

## LibreChat Web Application Overview

### Architecture
- Monorepo: `/api` (Express/Node backend), `/client` (React/Vite frontend), `/packages` (shared libs)
- MongoDB database, optional Redis, optional Meilisearch
- JWT-based auth with refresh tokens

### Core Features (Full Parity Required)

#### 1. Authentication & Onboarding
- **Server URL entry** (Android-only: user specifies their LibreChat server)
- Local login (email/password)
- OAuth2 social logins (Google, GitHub, Discord, Facebook, Apple, OpenID, SAML)
- Registration with email verification
- Password reset flow
- Two-factor authentication (TOTP + backup codes)
- Terms of service acceptance

#### 2. Chat (Primary Experience)
- Conversation list in sidebar (cursor-paginated, sorted by updatedAt)
- Create new conversations
- Send messages, receive streaming AI responses via SSE
- Message tree with parent/child relationships and branching
- Sibling message navigation (e.g., "1/3" switcher for regenerated responses)
- Edit user messages (creates new branch)
- Regenerate AI responses
- Continue incomplete responses
- Stop generation mid-stream
- Fork conversations from any message
- Duplicate conversations
- Markdown rendering with syntax highlighting
- LaTeX/math rendering
- Code blocks with copy button and language badge
- Image display (inline + expandable)
- File attachments in messages
- Tool call display (expandable cards showing input/output)
- Message feedback (thumbs up/down with optional comment)
- Typing/streaming indicator
- Time-based greeting on landing ("Good morning", etc.)
- Custom welcome messages from server config

#### 3. Model/Endpoint Selection
- Multiple AI providers: OpenAI, Anthropic, Azure, Google, Groq, Mistral, Ollama, custom
- Model selector dropdown with search
- Endpoint icons (branded)
- Model parameters (temperature, top_p, frequency_penalty, etc.)
- Model specs from server config

#### 4. Agents & Assistants
- Agent marketplace (grid of cards with avatar, name, description, category)
- Agent chat with tool calling
- OpenAI Assistants integration
- Agent/assistant selection UI

#### 5. File Management
- Upload files (images, PDFs, documents, code)
- Drag-and-drop (not applicable on Android, use file picker)
- File preview in messages
- File download
- Image generation display
- Audio recording for voice input (STT)
- Text-to-speech playback (TTS)

#### 6. Conversation Management
- Rename conversations (inline)
- Archive/unarchive
- Delete (single + bulk)
- Share (generate public link)
- Export (JSON, markdown)
- Import conversations
- Tags for organization
- Bookmarks/favorites
- Search conversations (full-text via Meilisearch)

#### 7. Presets
- Save chat configurations as presets
- Load presets
- Delete presets

#### 8. Prompts Library
- Create/edit/delete prompts
- Share prompts
- Prompt versioning
- @mentions for prompts in chat input
- Prompt variables

#### 9. User Settings
- Theme (dark/light)
- Language selection (46+ languages)
- Font size
- Speech settings
- Data export
- Account management (profile, delete account)
- Balance/credits display

#### 10. Artifacts
- Split-pane code preview/editor
- Tabs: Preview, Code, Info
- Live editing

---

## API Endpoints (Key Routes)

### Authentication
```
POST /api/auth/login        → { token, user } or { twoFAPending, tempToken }
POST /api/auth/register     → { status, message }
POST /api/auth/logout       → { status }
POST /api/auth/refresh      → { token, user } (refresh token in cookies)
POST /api/auth/requestPasswordReset
POST /api/auth/resetPassword
GET  /api/auth/2fa/enable   → { secret, qrCode }
POST /api/auth/2fa/verify
POST /api/auth/2fa/confirm  → { confirmed, backupCodes }
POST /api/auth/2fa/disable
POST /api/auth/2fa/verify-temp → { token, user }
```

### Configuration
```
GET /api/config             → startup config (models, features, auth methods, interface config)
GET /api/endpoints          → available AI providers and models  (JWT required as of v0.8.5)
POST /api/user/settings/favorites → update user favorites (agent/model pins, v0.8.5+)
GET  /api/user/settings/favorites → list user favorites (v0.8.5+)
POST /api/prompts/groups/:id/use  → record prompt-group usage for analytics (v0.8.5+)
```

**v0.8.5 notes**
- `GET /api/config` response payload is now split into a pre-auth and post-auth variant.
  Pre-auth fields (what `validateServerUrl` / `fetchStartupConfig` rely on) are unchanged
  from v0.8.4; post-auth adds fields driven by the logged-in user (not consumed by mobile).
- `GET /api/config` removed `instanceProjectId`. Mobile previously used it as an OR fallback
  in `ConfigRepositoryImpl.isValidLibreChatConfig`; cleanup landed in v0.8.5 sync.
- `GET /api/config` added `allowAccountDeletion: Boolean`. Mobile honors this and hides
  the Delete Account button when `false`. Defaults to `true` for older servers that omit the field.
- `GET /api/endpoints` now requires JWT (was public in v0.8.4). Mobile already called it
  post-auth, so this is non-breaking.
- Favorites schema: each entry has exactly one of `{ agentId }`, `{ model, endpoint }`,
  or `{ spec }` — three mutually exclusive variants enforced by the server
  (`FavoritesController.js`: "Each favorite must have either agentId, model+endpoint, or spec";
  `model` and `endpoint` must be supplied together; combining `spec` with any of
  `agentId`/`model`/`endpoint` is rejected). Server also enforces 50 entries max /
  256-character max per string; mobile short-circuits oversize writes. The `spec`
  variant is round-tripped unchanged (mobile does not yet render a spec picker).
  `POST` replaces the entire list (upsert-by-overwrite), and the response echoes the stored list.

### Out of scope (admin panel)
```
/api/admin/auth/**   → admin-only SAML + Social OAuth callbacks (v0.8.5, web-only)
/api/admin/config/** → admin YAML config endpoints
/api/admin/grants/**, /api/admin/groups/**, /api/admin/roles/**, /api/admin/users/**
```
Admin panel is a web-only surface in upstream; mobile intentionally does not implement it.

### Conversations
```
GET    /api/convos                          → { conversations, nextCursor }
GET    /api/convos/:conversationId          → single conversation
POST   /api/convos/update                   → update title
POST   /api/convos/archive                  → archive/unarchive
DELETE /api/convos                           → delete conversation(s)
POST   /api/convos/fork                     → fork from message
POST   /api/convos/duplicate                → duplicate conversation
POST   /api/convos/import                   → import (multipart)
GET    /api/convos/gen_title/:conversationId → generated title
```

### Messages
```
GET    /api/messages/:conversationId           → messages for conversation
GET    /api/messages/:conversationId/:messageId
POST   /api/messages/:conversationId           → save message
PUT    /api/messages/:conversationId/:messageId → update message
DELETE /api/messages/:conversationId/:messageId
PUT    /api/messages/:conversationId/:messageId/feedback
```

### Chat (Streaming)
```
POST /api/agents/chat         → { streamId } (start generation)
GET  /api/agents/chat/stream/:streamId?resume=true → SSE stream
POST /api/agents/chat/abort   → abort generation
GET  /api/agents/chat/active  → { activeJobIds }
GET  /api/agents/chat/status/:conversationId → job status
```

### Files
```
GET    /api/files              → user's files
POST   /api/files              → upload (multipart)
GET    /api/files/download/:userId/:file_id
DELETE /api/files              → delete file(s)
POST   /api/files/speech/stt   → speech-to-text
POST   /api/files/speech/tts   → text-to-speech
```

### v0.8.6 endpoint surface (confirmed during sync; mobile status noted)
```
# Skills family (DEFERRED — no mobile counterpart; skill tool *invocations* already
# render via the generic tool-call path, so chatting with a skill-enabled agent works today)
GET    /api/skills                       → list
POST   /api/skills                       → create (perm: skills.create)
GET    /api/skills/:id                   → detail
PATCH  /api/skills/:id                   → update
DELETE /api/skills/:id                   → delete
GET    /api/skills/:id/files             → skill file tree
POST   /api/skills/:id/files             → add file
GET    /api/skills/:id/files/:relPath    → read file
DELETE /api/skills/:id/files/:relPath    → delete file
POST   /api/skills/import                → import skill
GET    /api/user/settings/skills/active  → per-user active-skill states (DEFERRED)
POST   /api/user/settings/skills/active  → set active-skill states (DEFERRED)
PUT    /api/roles/:roleName/skills       → admin skill-permission grant (DEFERRED; admin)

# Deferred-preview poll for inline office-doc rendering (DEFERRED — pairs with TFile.status/text)
GET    /api/files/:file_id/preview       → { file_id, status: pending|ready|failed,
                                            text?, textFormat?: html|text, previewError? }
                                            (status defaults to 'ready' for legacy/non-office files)

# CloudFront signed-URL download (DEFERRED — only when server is CloudFront-configured;
# the existing /api/files/download/:userId/:file_id bytes path still works on v0.8.6)
GET    /api/files/download-url/:userId/:file_id → { url, filename, type, metadata }
```

### /api/config v0.8.6 behavior (verified non-breaking)
- Pre-auth (no `req.user`) response is now sanitized to a minimal pre-login payload; `?context=share`
  merges a public-share payload. Mobile onboarding (`ConfigRepositoryImpl.validateServerUrl`) only
  checks `serverDomain.isNotBlank()` (not stripped) and re-fetches the full config post-auth, so this
  is safe. New top-level keys: `rum`, `cloudFront`, `buildInfo`; new `interface` keys: `skills`,
  `buildInfo`, `autoSubmitFromUrl`, `retentionMode`. Mobile now PARSES these (C1) but gates no UI yet.

### v0.8.7 endpoint surface (confirmed during sync; mobile status noted)
```
# Pinned conversations (BUILT — drawer pin/unpin + pinned section, gated >= 0.8.7)
POST   /api/convos/pin                   → { arg: { conversationId, pinned } } → updated Conversation

# Chat Projects / folders (BUILT — full: data layer + move-to-project picker + drawer folder
# section + Projects index (ConversationsRoute.Projects) + ProjectChats detail (Nav3) + project CRUD)
GET    /api/projects                     → { projects: [TChatProject], nextCursor }
                                            ?cursor&limit&sortBy&sortDirection&search
POST   /api/projects                     → { name, description? } → ChatProject (201)   (NOT arg-wrapped)
GET    /api/projects/:projectId          → ChatProject
PATCH  /api/projects/:projectId          → { name?, description? } → ChatProject          (NOT arg-wrapped)
DELETE /api/projects/:projectId          → { deletedCount, modifiedCount }
PUT    /api/projects/conversations/:conversationId → { projectId|null } → { conversation, previousProjectId, projectId }
GET    /api/convos?projectId=<id|unassigned>       → conversations filtered by project (added projectId query param)

# Context-usage gauge (BUILT — gauge + SSE; context-projection seeds the gauge on chat
# open / model switch; gated on interface.contextUsage && >= 0.8.7)
GET    /api/endpoints/token-config       → { [endpoint]: { [model]: { context, prompt?, completion?, cacheWrite?, cacheRead? } } }
POST   /api/endpoints/context-projection → { conversationId, messageId, endpoint, model?, agentId?, spec?,
                                            maxContextTokens?, calibrationRatio? } → ContextUsage | null

# Shared links: getSharedLinks dropped the isPublic query param (visibility now ACL-governed).
GET    /api/share?cursor&pageSize&sortBy&sortDirection&search   (NO isPublic — removed in v0.8.7)
```

### v0.8.7 SSE additions (BUILT)
```
{ event: 'title',            data: { conversationId, title } }        # immediate title (interface.titleTiming === 'immediate')
{ event: 'on_token_usage',   data: TTokenUsageEvent }                 # per-call provider usage
{ event: 'on_context_usage', data: TContextUsageEvent }               # context-window snapshot (breakdown + remaining)
```

### /api/config v0.8.7 behavior
- New `interface` keys (mobile PARSES; gates noted): `contextUsage` (default true), `contextCost` (default false),
  `titleTiming` ('immediate'|'final'), `defaultPinnedTools: string[]` (detection-only), `sharedLinks`
  (bool | { create, share, public, snapshotFiles }), `maxCatalogSkills` (detection-only). New startup-level key:
  `sharedLinksSnapshotFilesEnabled` (detection-only). New conversation/preset field: `promptCacheTtl` ('5m'|'1h').
  New conversation fields: `pinned`, `chatProjectId`.

### v0.8.7 chat-payload addition (BUILT)
- The chat request now sends `timezone` (IANA id, e.g. "America/New_York"; #13815) so the server
  resolves agent `{{current_date}}`/`{{current_datetime}}` to the user's wall clock. Always sent,
  ungated; set in `ChatPayloadBuilder` from `TimeZone.currentSystemDefault()`.

### v0.8.7 known-deferred parity gaps (NOT built; tracked in proposal-v0.8.7.md)
- `url_context` conversation toggle (Google URL Context) — no mobile param-sheet control yet.
- per-message `quotes[]` round-trip (selected-text quote-reply context) — mobile neither sends nor renders.

### v0.8.8-line partial sync (untagged dev commit 6c97a7f4, 2026-07-23) — endpoint / shape changes
These landed upstream on the post-v0.8.7 `dev` branch (package.json still reports 0.8.7; the
target commit is untagged). Date-gated paths use `BackendVersion.supportsFeature`.
```
# Newly-discovered / revised request contracts
POST   <generation endpoint>             + top-level `clientRequestId` (uuid) idempotency key (#14344,
                                            landing commit for the target itself is #14411 stream-order).
                                            Server claims it before job creation so a replayed POST dedups
                                            to the original run instead of double-billing. Additive; mobile
                                            mints one per send in ChatPayloadBuilder. (BUILT)
POST   /api/auth/login                    now 403s when ALLOW_EMAIL_LOGIN=false (#14180). /api/config already
                                            exposes `emailLoginEnabled` (default true); mobile hides the
                                            email/password form off it and maps the 403 to a clear message.
                                            Config-driven, no version gate, fail-open. (BUILT)
DELETE /api/files                         reworked (#14149): agent-attached unlink 400s without a valid
                                            `tool_resource` ∈ {execute_code, file_search, image_edit, context,
                                            ocr}; the non-owner via-agent fallback was dropped. Mobile already
                                            complies (owner manager sends neither agent_id nor tool_resource;
                                            AgentFilesDelegate always routes a valid resource). (NO CHANGE — documented)
GET    /api/memories                      now returns EVERY memory of the user, agent-partitioned ones included,
                                            each with `agentId` (null = shared personal pool) and `agentName`
                                            (resolved server-side, present only when the caller may VIEW that
                                            agent). `tokenLimit`/`totalTokens` still count the shared pool only.
PATCH  /api/memories/:key                 + optional `?agentId=` query param — SELECTS THE PARTITION. Keys are
DELETE /api/memories/:key                   unique only *within* a partition, and the server's filter is
                                            `{ agentId: agentId ?? null }`, so omitting the param targets the
                                            shared pool: mutating an agent-scoped entry without it edits/deletes
                                            a different same-named shared entry (or 404s). Mobile threads
                                            `Memory.agentId` from the list row through repository → API. (BUILT)
POST   /api/memories                      + optional `agentId` in the body, partitioning the new entry to an
                                            agent. Mobile always omits it (shared pool) — no agent picker.

# Added
POST   /api/agents/chat/resume            { conversationId, actionId, + the paused turn's endpoint/model/agent
                                            config, plus `decisions[]` (tool approval) or `answer`
                                            (ask_user_question) }. Resumes a run paused for human-in-the-loop
                                            review. Shares the chat router's middleware, and the server replays
                                            the paused turn's graph config from the pending action, so a crafted
                                            resume cannot swap the agent or tool set — it recomputes the request
                                            fingerprint and 403s a mismatch, which is why the client pins the
                                            turn config at pause ARRIVAL, not at decision time. The continuation
                                            arrives on the SSE stream that is still open (a paused run never
                                            emits `final`). Pairs with the `on_pending_action` SSE and the
                                            `requires_action` job status. NOT version-gated: the client only
                                            calls it in response to a server-announced pause carrying an
                                            actionId, which is itself proof the route exists — a date gate would
                                            instead strand real pauses on any server built past the pinned
                                            commit (BackendCommitMap → null → gate false). See VERSION_GATES.md.
                                            (#13942 + #14139, landed 2026-06-29 / 2026-07-08) (BUILT)
POST   /api/agents/chat/steer             { conversationId, text, files? } → 202 { status: 'queued', steerId,
                                            position, conversationId }. Queues a message for injection into the
                                            run that is ALREADY generating, at its next tool boundary;
                                            `streamId === conversationId` as everywhere else. Carries the same
                                            PII-filter + moderation + rate-limit chain as a normal message, and
                                            re-checks the caller against the ORIGINATING run's agent ACL (read
                                            from job metadata, never the request body — a steer cannot swap the
                                            agent). Server caps: 16k characters (`STEER_MAX_LENGTH`), 10 queued
                                            per run, 10 attachments.
                                            Rejections are ROUTINE, not errors, and the `code` — not the status —
                                            decides the client's fallback: 404 NO_ACTIVE_RUN (send as a new turn),
                                            409 RUN_PAUSED / 429 STEER_QUEUE_FULL / 501 STEER_UNSUPPORTED (hold
                                            in the client queue). Mobile treats an unrecognized code and a
                                            bodyless 404 from a pre-0.8.8 server the same way, so a wrong gate
                                            answer degrades instead of losing the message. Mobile sends no
                                            `files` — steering here is text-only and a during-run send carrying
                                            attachments is routed to the follow-up queue instead.
                                            (#14220, landedDate 2026-07-14) (BUILT)
POST   /api/agents/chat/steer/cancel      { conversationId, steerId } → { removed }. Withdraws a still-queued
                                            steer before injection. No moderation pass (nothing model-bound
                                            yet). `removed: false` is a 200, not a failure: the cancel lost its
                                            race (already injected, or the run ended) and the client defers to
                                            the events it will receive. (#14220, landedDate 2026-07-14) (BUILT)

# Removed
POST   /api/endpoints/context-projection  REMOVED (#13953, landing commit 376370d6, 2026-06-25). The gauge is
                                            now computed client-side / seeded from the on_context_usage SSE, so
                                            the POST 404s on the 0.8.8 line. Mobile version-gates the call OFF
                                            (supportsFeature minVersion 0.8.8-rc1, landedDate 2026-06-26 — one day
                                            past the landing, because three commits merged earlier that same day
                                            and the date gate is day-granular; see VERSION_GATES.md);
                                            < 0.8.8 backends keep the POST path. Inverts the >= 0.8.7 enable gate. (BUILT)
```

Response envelopes on the memories routes (unchanged by this cycle, corrected here because the
partition work above sits on them): the list route answers `{ memories, totalTokens, tokenLimit,
charLimit, usagePercentage }`, POST answers `{ created, memory }`, PATCH `{ updated, memory }`,
PATCH `/preferences` `{ updated, preferences: { memories } }`, DELETE `{ deleted }` — none of them
return the bare entity. `/preferences` also READS `{ memories: boolean }`, not `{ enabled }`. Memory
rows carry `updated_at` (snake_case) and no creation timestamp at all.

**Out of the 0.8.8 sync's scope, and not version-gated.** These envelopes are identical in v0.8.4,
v0.8.5, v0.8.6 and on the 0.8.8 line, so decoding them as bare entities was a pre-existing client
breakage against *every* supported server, and correcting it changes the memories screen's runtime
behavior on all of them — the list can now produce rows, edit/delete now hit the row the user picked,
and the enable toggle now reaches the server. It rode this branch only because the agent-partition
work (F9) sits on top of it and was otherwise unreachable — see the mandatory device-test item below.

### Mandatory device-test item: memories screen on a pre-0.8.8 server

Tracked apart from the 0.8.8 feature test plan because it is the only change on this branch whose
blast radius is every supported server, and because the 0.8.8 dev server cannot verify it: the
envelopes above are identical from v0.8.4 through the 0.8.8 line, so a pass there says nothing about
the older servers this also changes. Walk it against a v0.8.6 or v0.8.7 server before merge:

- **List** — the screen populates instead of showing the empty/failed state it showed before.
- **Edit** — editing a row changes that row's value and the change survives a reload.
- **Delete** — deleting a row removes that row and no other.
- **Enable toggle** — flipping memory on/off round-trips and survives a reload (the request used to
  send the `enabled` key, which the server rejects).
- **Row rendering** — rows show a last-updated time (from `updated_at`); no creation time exists.
- **Agent partitions (F9, dev server only)** — an agent-scoped row edits/deletes inside its own
  partition and leaves a same-key shared-pool row untouched.

### v0.8.8-line endpoints (built)
```
GET    /api/agents/:id/versions           → Agent[] version history. Requires EDIT on the agent; loaded lazily
                                            because histories are large — /expanded now answers with a `version`
                                            count and no `versions[]`. Mobile fetches it when the history sheet
                                            opens, guarded on the list already being empty so pre-0.8.8 servers
                                            (which still inline the array) pay for no second request.
                                            (#13977, 12fea693b, landed 2026-06-26)
GET    /api/user/settings/favorites/tools → TToolFavorite[] ({ itemType, itemId })
PUT    /api/user/settings/favorites/tools/:itemType/:itemId  → the added { itemType, itemId }
DELETE /api/user/settings/favorites/tools/:itemType/:itemId  → { ok: true }
                                            itemType ∈ {builtin, tool, mcp, skill}; itemId capped at 256 chars
                                            and 100 favorites per user, 400 otherwise. This is the real backend
                                            that replaced the v0.8.6 "skill favorites" client stubs; mobile now
                                            builds against it, so that backend-gap ledger entry is CLOSED.
                                            Gate: supportsFeature("0.8.8-rc1", landedDate 2026-07-05), plus a
                                            404 fallback that turns pinning off rather than reporting a failure.
                                            (#13952, landedDate 2026-07-05)
POST   /api/share/:shareId/fork           { targetMessageIndex? } → 201 with the forked conversation. Continues
                                            a SHARED conversation as the caller's own copy — distinct from the
                                            existing POST /api/convos/fork mobile already calls. Wired through
                                            ShareRepository but with NO caller: mobile has no shared-link viewer
                                            to fork from. Ungated — a pre-0.8.8 server 404s, which is the error
                                            a future caller has to handle anyway. (#13714, landedDate 2026-06-24)
POST   /api/files/usage                   { file_ids } → { held } (was { marked } before #14470). A RENEWABLE
                                            BOUNDED HOLD, not a release, so uploads sitting in a client-side
                                            queue are not reaped before they drain:
                                            expiresAt = max(expiresAt, min(now + renewMs, createdAt +
                                            maxLifetimeMs)) where renewMs = 24 h + checkpointer.ttl
                                            (FILES_USAGE_BASE_HOLD_MS) and maxLifetimeMs = 24 h +
                                            checkpointer.ttl × 8 (FILES_USAGE_QUEUED_RUN_ALLOWANCE). Widen-only,
                                            and `expiresAt: { $exists: true }` means an already-released file
                                            never gets a TTL back. Replay converges on a per-file ceiling
                                            instead of pinning forever, so a client that stops touching lapses
                                            one renewMs after its last call. No longer $inc: usage — a queue
                                            touch is not a send, and queued-then-drained files were landing at
                                            usage: 2. Called when a message is enqueued as a follow-up; capped
                                            at 10 ids per call server-side (upstream QUEUE_USAGE_MAX_FILES), so
                                            the repository chunks rather than forfeiting a whole batch. Exempt
                                            from the upload rate limiter ONLY on the 0.8.8 line that added it —
                                            older servers limit every POST under /api/files except /speech, so
                                            the call is version-gated (supportsFeature 0.8.8-rc1, landedDate
                                            2026-07-14); error code FILES_USAGE_FAILED. NOW METERED by its own
                                            per-user limiter — FILE_USAGE_USER_MAX (default 120) per
                                            FILE_USAGE_USER_WINDOW (default 15 min), 429 { message: "Too many
                                            file usage requests…" } — and a breach LOGS A FILE_UPLOAD_LIMIT
                                            VIOLATION scored by FILE_UPLOAD_VIOLATION_SCORE, so it is no longer
                                            a free call. Still off the upload quota; trailing slash normalized
                                            (/usage/ hits the same limiter). Web renews on a 30-min heartbeat
                                            while anything is queued (useQueueDrain); mobile matches it —
                                            MessageQueueDelegate.startHoldRenewal touches at enqueue, then
                                            renews the whole queue every 30 min on the ChatViewModel scope.
                                            Deliberately a no-op once that scope or the process is gone: the
                                            queue is never persisted, so there is nothing left to hold. Mobile
                                            stays on the multipart-JSON upload path — #14295 / 2026-07-21 is the
                                            separate upload-SSE heartbeat work under F8, which is NOT adopted.
```
UI shipped alongside them: the unified Tools Marketplace picker in the agent editor (one catalog over
built-in capabilities, plugin tools, MCP servers and skills, with per-item favorites), the MCP OAuth
consent dialog, and agent contact info on agent detail.

Sandbox `read_file` images (U9) needed no rendering change: the tool builds its artifact as an inline
`data:` URI, but the agent callback runs `saveBase64Image` over every `image_url` part before emitting
the attachment, so the client receives a stored `/images/…` path and renders it through the existing
tool-call attachment path. `ImageUrlResolver` gained a `data:` passthrough as defence in depth only —
it is unreachable against the pinned server and fixes nothing that was broken. If a sandbox image is
observed not rendering, the fault is in the tool-call attachment path, not here.

Deliberately NOT ported from the same upstream window, each because it needs a mobile surface that
does not exist or is pointer-specific web polish: upstream's OrchestrationHub and StatefulSessions
panels (agent-to-agent orchestration and sandbox session reuse), the `on_sandbox_starting` cold-boot
indicator, the MessageNav rework (a pinned scroll-to-bottom rib and hover chevrons; mobile already has
a scroll-to-bottom FAB), and the web touch select/drag fixes. None affects wire compatibility.

Revised message / SSE shapes:
- Message content parts add a `steer` type (`type == "steer"`, #14220) — mid-run steering. `ContentType`
  gained `STEER` and `MessageContentPart` a nullable `steer: JsonElement?`, so a persisted message carrying
  it deserializes instead of throwing `SerializationException` on conversation load (`ignoreUnknownKeys` does
  NOT rescue an unknown enum value). Parsed, not yet rendered as its own bubble: the injected instruction is
  visible through the reply it steers. (BUILT)
- `on_steer_applied` (#14220) — a queued steer reached a tool boundary and went into the run. The injected
  text rides the nested `part` (the `steer` content part above), not the top level, and the event races its
  own HTTP 202: it regularly arrives naming a `steerId` the sending client has not learned yet, so a consumer
  must RECORD applied ids rather than only remove a chip that may not exist. (BUILT)
- `resumeState.pendingSteers` on the sync frame (#14220) — the steers still queued for injection, replayed on
  reconnect. A full authoritative snapshot, not a delta: an EMPTY list is meaningful (chips the client is
  still showing were drained), while an ABSENT key is not (a server with no steering says nothing). (BUILT)
- SSE resumable-stream ordering is now preserved across turns (#14411 — the pinned target commit). Server-side
  ordering fix on resume/reconnect; no wire-shape change, transparent to the client.

Additive response fields (parse-layer only unless a row says BUILT):
- `GET /api/agents/chat/status/:conversationId` — adds `status` (`running` | `requires_action` | terminal),
  `pendingAction` (client-safe projection of a run paused for tool approval / `ask_user_question`;
  `requestFingerprint` and `resumeContext` are stripped server-side, so it must never be echoed back), and
  `unrecoveredSteers[]`. `active: true` now also covers a paused run, so it is NOT "tokens are arriving".
  **`unrecoveredSteers` is claim-on-read**: the server clears them once returned, so a client that ignores
  the list drops the user's queued words permanently. Only populated when the run is not active. Mobile now
  claims them on every resume-status read and re-homes them as queued follow-ups. (BUILT)
- `POST /api/agents/chat/abort` — adds `pendingSteers[]` (steers queued mid-run that never reached an
  injection boundary, handed back exactly once). `aborted` (the stream id actually aborted) already existed
  at v0.8.7 and is only newly modeled on mobile. The `final` frame carries the same `pendingSteers` list for
  a run that ended normally, so between the two every ending has a report. Mobile consumes both and turns
  them into queued follow-ups; a stream that dies on an error carries no report at all, and there the
  locally-held chip text is converted instead. (BUILT)
- `GET /api/user/terms` — adds `termsAccepted` / `termsAcceptedAt`; `POST /api/user/terms/accept` now returns
  `{ message, termsAcceptedAt }` instead of an empty body. `GET /api/user` adds `termsAcceptedAt`.
- `POST /api/mcp/:serverName/reinitialize` — adds `connectionDeferred`: the reinitialize was accepted but the
  connection is being established in the background, so `success` does not mean the server is reachable.
- Conversation + preset — add `reasoning_mode` / `reasoning_context` (Responses-API siblings of
  `reasoning_effort`); agents add `stateful_code_sessions` (persistent code-interpreter sandbox across a run's
  tool calls) and `memory_scope` (`"agent"` isolates memories per user+agent, `"user"`/null = shared pool).
  Round-tripped so a mobile edit doesn't drop what was set on web; no mobile editor controls.
- Model spec — adds `showInMenu`. The server already drops `showInMenu: false` specs from `/api/config`, so
  mobile never receives one; a hidden spec stays resolvable by name on a conversation.
- `GET /api/config` — adds `fileUploadSseEnabled` (`FILE_UPLOAD_SSE_ENABLED`, off by default). Detection-only:
  mobile stays on the multipart/JSON upload path regardless.

File-picker accept types (upstream `client/src/hooks/Files/useUploadOptions.ts`): the picker is filtered to
the endpoint's `supportedMimeTypes` allowlist from `GET /api/files/config`, translated into concrete types via
the `fullMimeTypesList` mirror in `core/model/.../PickerMimeTypes.kt` (a regex can't be handed to a native
picker). Applied on the two surfaces whose accept set upstream derives from that allowlist — the chat composer
attach and the files manager. The agent editor's code / knowledge / context pickers stay unrestricted on
purpose: upstream sources their accept sets from the per-`tool_resource` lists
(`codeInterpreterMimeTypesList`, `retrievalMimeTypesList`), not from `supportedMimeTypes`, so filtering them
on this allowlist would be the wrong restriction.

### v0.8.8-line partial sync (untagged dev commit 91adcf3f, 2026-07-29) — SSE / shape changes
Continues the range above; `package.json` still reports 0.8.7 and the commit is still untagged.
- `on_activity_label` (#14391) — the activity-group header over a reasoning+tool block.
  `{ index, part: { type:'activity_label', activity_label, tool_call_ids?, counts?, status?,
  agentId?, pending? }, responseMessageId?, conversationId? }`, where `index` is the ABSOLUTE
  content index. **Two emissions per block**: an empty reservation at the tool-batch boundary
  (`activity_label: ""`, `pending: true`), then the resolved label once the fast label model
  answers. Mobile drops the live event through `SseEventMapper`'s forward-compat `else -> null`
  and renders labels from persisted content instead — the same posture already documented for
  `on_subagent_update`. The persisted part is what matters; see the `MessageContentPart` note.
- `usage_type` on `on_token_usage` (#14391) — `summarization` | `subagent` | `sequential` |
  `activity-label`. Present ONLY on non-primary buckets; absent on the turn's own model call.
  These are separate model calls and must be **EXCLUDED** from the live context gauge and the
  breakdown sheet — mobile's handler is last-write-wins, so a bucketed event that gets through
  replaces the turn's Input/Output figures with the bucket's. Activity labels make that acute:
  one usage event per tool batch, default up to 20 per run, from a cheap fast model. Modeled as
  a plain nullable String, never an enum, so a bucket upstream adds later stays inert (non-null
  ⇒ excluded) rather than failing the decode. (BUILT)
- `GET /api/agents/chat/stream/:streamId` no longer waits for a subscriber before generation
  starts (#14423), and resume subscriptions are two-phase server-side (`activate()` after the
  sync frame). Contract-identical for the client and requires no change — recorded because it
  is the kind of thing that would look like the cause of a future resume bug.

### Other
```
GET/POST/DELETE /api/presets
GET/POST/PUT/DELETE /api/prompts
GET/POST/DELETE /api/tags
GET/POST/PATCH/DELETE /api/share
GET /api/balance
GET /api/search
GET /api/user
GET /api/banner
```

---

## Data Models

### User
- id, name, email, username, avatar, role, provider
- emailVerified, twoFactorEnabled
- favorites, termsAccepted

### Conversation
- conversationId (UUID), title, user, endpoint, model
- agent_id, assistant_id, tags, isArchived
- Model parameters (temperature, top_p, etc.)

### Message
- messageId (UUID), conversationId, parentMessageId
- user, sender, text, content (structured parts)
- isCreatedByUser, model, endpoint
- files, attachments, feedback
- error, unfinished, finish_reason, tokenCount

### MessageContentPart
Discriminated by `type`: `text`, `think`, `text_delta`, `tool_call`, `image_file`,
`image_url`, `video_url`, `input_audio`, `agent_update`, `summary`, `activity_label`,
`steer`, `error`.

`steer` (v0.8.8 line, #14220) and `activity_label` (v0.8.8 line, #14391) both PERSIST into
saved message content, so both must be declared client-side: `ContentType` has no property
default, so an undeclared value is not rescued by `ignoreUnknownKeys` and fails the whole
message decode — which takes conversation load with it. The `steer` omission above was a
documentation gap from the prior sync, not a new value. Both are now rendered as well as
declared (see `feature/chat/CLAUDE.md`), from persisted content only — mobile drops the live
`on_activity_label` event through the forward-compat `else -> null` branch.

`activity_label` carries its label as a top-level plain string (`{"type":"activity_label",
"activity_label":"Searched the codebase", "tool_call_ids":[…], "counts":{…}, "status":…,
"agentId":…, "pending":…}`); mobile models the label, `pending` and `status`, and
`tool_call_ids`/`agentId` already existed on the part. `counts` is still unmodelled. Empty
label + `pending: true` is the reservation form, which renders as nothing.

`steer` carries the user's text as a top-level plain string alongside `steerId`, `files` and a
`createdAt` that is **epoch millis, a number** — unlike every other part's ISO-string
`createdAt`. Mobile shares one `createdAt: String?` field across part types, so only
`librechatJson`'s `isLenient` keeps that from throwing; dropping that flag would fail the whole
`GET /messages` decode. `steerId` and `files` are unmodelled (steer attachments render as
text-only).

**SUMMARY part wire shape (v0.8.5+)** — context-compaction emits a content part with
fields at the top level (not nested under a `summary` key):
```
{
  "type": "summary",
  "content": [{"type":"text","text":"..."}],  // array OR string (two variants)
  "tokenCount": 42,
  "summarizing": false,
  "summaryVersion": 1,
  "model": "gpt-4o",
  "provider": "openai",
  "createdAt": "2026-04-22T...",
  "boundary": {"messageId": "...", "contentIndex": 0}
}
```
Variants for the body text (mirrors upstream `BaseClient.getSummaryText`, last-wins):
1. `content: Array<{type:"text", text}>` — new default since v0.8.5.
2. `content: string` — intermediate variant; rare but emitted by some code paths.
3. No `content`; `text: "..."` at the top level — legacy fallback from pre-v0.8.5
   summarization or test fixtures.

Mobile's `MessageContentPart.content` is typed `JsonElement?` to absorb variants 1/2,
and falls back to the existing `text: String?` field for variant 3.

### File
- file_id, filename, filepath, type, bytes, source
- user, conversationId, messageId

---

## SSE Streaming Protocol

### Flow
1. POST to `/api/agents/chat` with message payload → returns `{ streamId }`
2. Connect SSE to `GET /api/agents/chat/stream/:streamId`
3. Receive events: message, step, created, attachment, final, sync, error
4. On disconnect: reconnect with `?resume=true` for sync event

### Agent-library event names (v0.8.5)
`on_message_delta`, `on_reasoning_delta`, `on_run_step`, `on_run_step_delta`,
`on_run_step_completed`, `on_chat_model_end`, `on_agent_update`, `attachment`, and —
added in v0.8.5 — `on_summarize_start`, `on_summarize_delta`, `on_summarize_complete`.

`on_summarize_complete` payload nests the finished summary block under a `summary` key
(distinct from the message-persistence SUMMARY content part described in
`MessageContentPart`):
```
{"id":"...","agentId":"...","summary":{"type":"summary","content":[{"type":"text","text":"..."}],...}}
```
Mobile only renders the compacted summary once it is persisted to the final message as
a SUMMARY content part; the delta/lifecycle events are surfaced as status only.

v0.8.6 adds `on_subagent_update` (envelope `{ event, data:{ phase, parentToolCallId, data } }`)
for the web subagent live-trace dialog. Mobile drops it via `SseEventMapper`'s forward-compat
`else -> null` branch with no crash; the subagent's actual reasoning/tool-calls/text still
arrive folded into the parent run's normal `on_run_step`/`on_message_delta` events, so
subagent activity renders today as ordinary parent-agent tool calls. A dedicated trace UI is
deferred.

### SSE Event Format
```
event: message
data: {"type":"content","chunk":"...","status":"streaming"}

event: message
data: {"type":"tool_call","toolName":"...","input":{...}}

event: message
data: {"sync":true,"resumeState":{"runSteps":[...],"aggregatedContent":[...]}}
```

### Reconnection
- Exponential backoff: 1s, 2s, 4s, 8s... max 30s
- Max 5 retries
- Resume preserves state via sync event

---

## Authentication Details

### Token Management
- JWT access token in `Authorization: Bearer <token>` header
- Refresh token stored as HTTP-only cookie (for web; Android should store securely)
- Access token expiry: ~15 minutes
- Refresh token expiry: ~24 hours
- Auto-refresh on 401 response

### OAuth Flow (Android)
- Open browser/Custom Chrome Tab for OAuth provider
- Callback redirect to app via deep link
- Exchange code for token

---

## UI/UX Reference (from Web App)

### Theme
- Light: White bg (#fff), text #212121, surface hover #e3e3e3
- Dark: #0d0d0d bg, text #ececf1, surface hover #424242
- Material 3 equivalents should be used on Android

### Key Screens
1. **Server URL Entry** (Android-only onboarding)
2. **Login/Register** with social login buttons
3. **Chat List** (sidebar on web → drawer or dedicated screen on Android)
4. **Chat View** (messages + input)
5. **Landing/New Chat** (greeting + model icon)
6. **Model Selector** (dropdown → bottom sheet on Android)
7. **Settings** (tabbed → Material 3 navigation)
8. **Agent Marketplace** (grid of cards)
9. **Search** (full-text search)
10. **File Viewer/Picker**

### Navigation Patterns (Android Adaptation)
- Web sidebar → Navigation drawer or bottom navigation
- Web modals → Bottom sheets or new screens
- Web dropdowns → Material 3 menus or bottom sheets
- Web hover actions → Long-press menus or always-visible icons

### Responsive Behavior
- Single-pane on phones (chat list or chat view, not both)
- Potential dual-pane on tablets
- Bottom navigation for primary actions
