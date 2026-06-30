# LibreChat Mobile - Discovery & Context Document

## Project Goal
Build a native mobile app (LibreChat-Mobile) with full feature parity to the LibreChat web application. The app must connect to an existing LibreChat backend server (no backend changes). Users specify the server URL during onboarding.

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
`image_url`, `video_url`, `input_audio`, `agent_update`, `summary`, `error`.

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
