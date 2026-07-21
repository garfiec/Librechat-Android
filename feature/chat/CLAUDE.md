# feature:chat

## State Architecture (ChatUiState slices + narrowed handles)
`ChatUiState` is decomposed into **16 `@Immutable` sub-state slices** (15 new files under
`viewmodel/state/`, plus the pre-existing `comparisonState`), plus two top-level fields: `error`
(a shared transient-banner channel) and `mediaPreview`. The slices: `conversation`, `content`
(message tree + all streaming fields),
`editing`, `composer`, `selection` (endpoint/model/tools/params), `queue`, `search`,
`presetPrompts`, `voice`, `favorites`, `subagents`, `comparisonState`, `gates`, `account`,
`actions`, `prefs`.

- **Compat accessors.** `ChatUiState` keeps flat `val field get() = slice.field` accessors for
  every former field, so the ~250 UI read sites and test assertions read `uiState.isStreaming`
  etc. unchanged. UI still reads flat; only writes changed.
- **Narrowed write handles.** Delegates never receive the root `ChatStateHandle`. Each gets a
  per-delegate handle from `DelegateHandles.kt` (e.g. `StreamingHandle`, `QueueHandle`) whose
  `update { … }` block exposes only the slices that delegate may mutate (compile-time enforced
  via a `*Writes` class). `error` is writable from every handle (shared channel); use
  `setError(msg)` or assign `error` inside an `update`. Reads stay global via `handle.state`.
  `ChatViewModel` alone holds the root `ChatStateHandle` for its orchestration transactions.
- **Atomic-transaction invariant.** The completion-flash finalize, begin-stream, reset-with-
  carryover, `applyComposer`, and `switchBranch` each write within a single slice (or a single
  writer block), so they remain **one** `StateFlow` emission — do not split them across multiple
  `update`/`copy` calls. Message-tree + streaming fields deliberately live in ONE slice
  (`MessagesState`) because all five couple them.
- Adding a field: put it on the owning slice, add a flat compat accessor on `ChatUiState`, and
  add the slice to the writer of whichever delegate(s) own it.
- **Chrome/hot collection split (both `ChatScreen` actuals).** The screen collects `uiState` twice:
  the thread subtree (`ChatContent` on Android, `IosChatBody` on iOS) at full rate, the rest — top
  bar, composer, dialogs, sheets, effects ("chrome") — as
  `map { it.neutralizeStreamingChurn() }.distinctUntilChanged()`
  (`ChatChromeEquivalence.kt`), so the 50ms flush doesn't re-execute the whole screen ~20×/s.
  The neutralized fields are always empty on the chrome's copy — chrome must not read them; if it
  needs one, drop it from `neutralizeStreamingChurn`. New high-frequency streaming fields go in
  both `neutralizeStreamingChurn` and `ChatChromeEquivalenceTest`. Known exclusion:
  `subagentProgress` is chrome-visible by design (ChatRoot → `LocalSubagentProgress`) and written
  per SSE envelope unthrottled, so subagent-heavy runs still re-execute the chrome — needs a
  separate collection path or a write-side throttle in `SubagentTraceDelegate` (open follow-up).

## Screen States
`ChatScreenState` enum: `LANDING` | `LOADING` | `ACTIVE`
- **Landing**: no conversation selected, shows greeting + model icon + optional conversation starters
- **Loading**: spinner while fetching messages for a conversation
- **Active**: message list + streaming content + input area

## Navigation
- Sealed interface: `ChatRoute : NavKey` with typed route classes
- Routes: `NewChat` (landing), `Chat(conversationId: String? = null)`, `PromptsLibrary`, `PromptEditor(groupId: String? = null)` (all `@Serializable`)
- Feature entries registered via `EntryProviderScope<NavKey>.chatEntries()`
- When a new conversation starts from the landing page, `pendingNavigationConversationId` triggers navigation to `Chat(id)` at `StreamEvent.Created`, keeping `NewChat` in the back stack. The landing VM is reset (`onPendingNavigationHandled`) and the new Chat(id) VM resumes the active stream — so the Chat(id) VM (with `isNewConversation = false`) is the one that handles the first stream's Final. New-chat-only work there (title generation) is gated on `isHandedOffNewChat`, derived from consuming `NewChatSelectionHandoff`
- `navigateToChat()` replaces the current chat entry on the `NavBackStack` when switching between chats so back returns to `NewChat`

## Message Tree
- Messages stored flat, linked by `parentMessageId` (root = `NO_PARENT` UUID)
- `MessageNode.kt` builds the tree via `buildActiveMessagePath(messages, activeBranches, streamingLeafId)`
- `activeBranches: Map<String, Int>` tracks which sibling is displayed per parent
- `switchBranch(parentMessageId, siblingIndex)` updates branch selection and rebuilds display list
- Editing a user message creates a new sibling (new branch); regenerating an AI message also creates a sibling

### Streaming anchor (in-place edit/regenerate)
- The streamed reply is rendered by `MessageList` as a trailing `StreamingMessageBubble` *outside* the tree — the mobile divergence from web, which streams into an in-tree `initialResponse` placeholder node.
- So during edit/regenerate the bubble must land where the new branch belongs, not after the stale one. At stream start each send path rebuilds `displayMessages` via `buildActiveMessagePath(messages, activeBranches, streamingLeafId)`, where `streamingLeafId` is the message the in-flight reply attaches to: the path is truncated there (and that branch is forced over any `activeBranches` override), so the trailing bubble renders as its pending child.
- The leaf is the optimistic user message for `doSendMessage` / `editUserMessage` (passed inline alongside the message insert), and the existing parent user message for `regenerateMessageNow` / `editAiMessage` (via `anchorStreamTo`).
- This is a one-shot rebuild — there is no stored anchor field. The truncated `displayMessages` just persists in state for the stream's duration. On Final the untruncated path is rebuilt **in memory** by `finalizeChatDisplay` (normal + temp chats); only comparison chats still rebuild via a background `loadConversation`/Room reconcile. The full tree stays in `messages`/DB, so the old branch remains reachable via sibling navigation.
- **Invariant (load-bearing):** while streaming, nothing may write to Room or mutate `activeBranches` — otherwise the `loadConversation` observer re-emits and rebuilds `displayMessages` with no `streamingLeafId`, un-truncating the path and clobbering the in-place view. The send paths uphold this (no mid-stream Room write); `switchBranch`, `editMessage`, and `regenerateMessage` are all gated on `!isStreaming` so user taps can't break it either.
- **Handed-off new chat (load-bearing):** when a chat is created from the landing page, the landing VM is reset and a fresh `Chat(id)` VM resumes the stream — so the optimistic user message lives only in the discarded landing VM, and the server persists the *request* message only when the reply completes (`agents/request.js` saves it right before the Final event). Without intervention the resumed screen would show only the streaming bubble (no user message) for the whole stream. Fix: `NewChatSelectionHandoff` carries the optimistic `Message`; `ChatViewModel.init` seeds it into `messages`/`displayMessages` and into `pendingResumeUserMessage`. `loadConversation` keeps that seed appended — inside its off-Main `combine` map, guarded by a `none { id matches }` check so it only appends when the server hasn't echoed it yet — until the server echoes its own copy *by id*, then drops it (so a later server-side delete still removes the row). `finalizeChatDisplay` *also* clears `pendingResumeUserMessage` in its atomic Final update, so a backend that never adopts the client-minted id can't strand the seed and re-append it as a phantom sibling. This is what feeds `finalizeChatDisplay`'s in-memory backfill in the handoff case — do **not** replace it with a network `reloadConversation` on Final (that was the #169 regression being avoided).
- **Optimistic id reconciliation:** `doSendMessage` / `editUserMessage` mint the optimistic user message's id and send it as `ChatRequest.messageId` (the `userMessageId` arg on `startChat`), so the server adopts it and echoes it in the Final `requestMessage`. This is what lets the optimistic message reconcile by id — for normal and temp chats alike via `mergeFinalMessagesInMemory` (`finalizeChatDisplay`, in-memory). Normal chats additionally persist the finalized turn to Room via `cacheMessages` (no network reload); the response's `parentMessageId` matching the adopted id is also what lets `finalizeChatDisplay` backfill the user message into the returned turn when a loose Final payload omits `requestMessage`, so the cached turn never drops it. Mirrors the web client's top-level `messageId`; without it the optimistic message would linger as a phantom sibling. Regenerate/continue/edit-AI send no `messageId` (they create no new user message).
- `editAiMessage` resubmits the parent user turn (regenerate + `isEdited`); it does **not** persist the typed assistant edit (that's `saveEditOnly` → `updateMessageText`, the web `updateMessage` analog). Web additionally seeds the placeholder with the edited content for a transient preview — not ported.
- **Completion render (load-bearing):** `MessageList` keys its `LazyColumn` items by the conversation **slot** (`MessageNode.treeParentKey`), and the trailing streaming bubble is keyed to the slot its finalized message will occupy (the last visible message's id == the reply's parent). This makes the streaming→final swap an in-place update of one item rather than a remove+add, so the list never loses its scroll anchor at completion. The finalize update is also atomic (it clears the streaming fields in the *same* `stateHandle.update` that swaps in the message — see `finalizeChatDisplay`), and the last message renders markdown synchronously via `LocalImmediateMarkdown` so it doesn't mount through a zero-height async-parse frame. Reverting any of these (per-id keys, a separate streaming-clear update, async parse for the last message) reintroduces the completion flicker.

## Compare Models (parallel responses)
- A comparison send carries an `addedConvo` (secondary agent/model) alongside the primary; the server runs both agents in parallel and persists ONE assistant message whose content parts each carry an `agentId`. The added/secondary agent's parts are suffixed `____N` (real `agent_…____1`, or ephemeral `endpoint__model___sender____1`); the primary's are unsuffixed.
- **Live streaming attribution:** SSE deltas carry only a step id, never an agentId. `SseEventMapper` records each `on_run_step`'s agentId keyed by step id and resolves deltas by their own step id — required because two agents' run steps interleave, and a last-write-wins would collapse both panes onto one agent. `ComparisonModeDelegate.isSecondaryEvent` then fans deltas into the primary/secondary buffers.
- **Rendering (`ChatContent.buildComparisonDisplayMessages`):** each pane filters the parallel message's parts via `util/ParallelContent.partsForPane` — so it works for every comparison turn in history, not just the live one. The captured streaming buffer is only a Final→reload-gap fallback. The single (non-comparison) list runs `collapseParallelToPrimary` so a branched-away comparison never renders both agents concatenated.
- **Reopen (`ComparisonModeDelegate.rehydrateFromMessage`):** nothing but the persisted attribution records that a conversation was a comparison, so `loadConversation` rehydrates comparison state from the assistant tail's parts on the first non-empty emission (latched once per load; respects a session toggle-off). Continue-with-response = `POST /api/messages/branch` filtered to one agent's parts; the branched sibling becomes a single-agent tail, so the next reopen is the normal view.

## Content Rendering
- `ContentPartRenderer` dispatches by `ContentPart.type`:
  - `text` -> `MarkdownContent` (custom regex parser with LaTeX support)
  - `think` -> collapsible thinking block
  - `tool_call` -> `StreamingToolCallCard` (expandable, shows args/output)
  - `image_file` / `image_url` -> inline AsyncImage, tap opens `FullscreenImageViewer`
  - `error` -> red-styled error display
- `CodeBlock` renders fenced code with syntax highlighting, language badge, and copy button (3s checkmark)
- LaTeX: `LatexBlock` (block math) and `LatexInline` (inline math) via AndroidMath native rendering
  - Supports `$$...$$`, `\[...\]` (block) and `$...$`, `\(...\)` (inline)
  - `$...$` requires heuristic check (`looksLikeLatex()`) to avoid false positives on dollar amounts
  - Falls back to monospace text if AndroidMath can't parse the expression

## Streaming
- `ChatViewModel.sendMessage()` calls `ChatRepository.startChat()` which returns `Flow<StreamEvent>`
- Two-phase: POST /api/agents/chat -> streamId, then GET SSE stream
- `streamingBuffer: StringBuilder` accumulates text deltas
- Tool calls tracked in `activeToolCalls` list (start -> complete lifecycle)
- **Stream termination is a chokepoint** for every *event-driven* end — clean/aborted Final, error, failed abort, watchdog, resume-found-expired — which funnel through the single private `endStream(reason)` in `StreamingManagerDelegate` (the one exception is a flow that ends with neither Final nor Error — a clean SSE EOF or a stream-GET 404 — which the caller's `onTerminated` safety net clears directly; a known gap, don't rely on `endStream` being the *only* teardown); the sealed reason (`Finalized` / `StreamError` / `AbortFallback` / `ResumeExpired`) decides job cancel, state write, queue policy, and whether a reload is allowed. A per-session Int counter latches each session to at most ONE end, and stale ends from a previous session are no-ops — do not add teardown steps at call sites; add them to the reason table. Structural invariant this encodes: **nothing reloads on an abort path** — the server emits the aborted frame BEFORE persisting, so any refetch there races the save (and the optimistic user message was never in Room, so a reload can lose it entirely). `Finalized` writes no state: the atomic finalize in `finalizeChatDisplay` owns that (the no-completion-flash invariant)
- `stopGeneration()` POSTs `chatRepository.abortChat()` and **deliberately does not cancel the stream job.** The abort POST only acks (`{ success, aborted }`); the server ends the run by emitting an ordinary `final` frame flagged `aborted` over the same SSE stream, and *that* frame carries the partial's content parts. Cancelling the collector was the original bug — the stopped reply vanished. `handleFinal` reads `aborted` off the frame, not off local stop state, so a Stop from another client is handled identically. Stop-specific behavior: no auto-TTS, no `gen_title`, no queue drain (the hold is re-asserted). `abortRequested` guards double-taps. Works before the `created` milestone too — a null stream id makes the abort route fall back to one of the caller's active jobs (the *oldest*, and the client can't tell which, since `ChatAbortResponse` drops the returned `aborted` id — so with a second stream live server-side a null-key Stop can hit the wrong job; known gap). An acked abort arms a 15s watchdog; if the frame never lands (dead socket) or the POST fails, `endStream(AbortFallback)` stops locally with the partial preserved and NO reload
- **Aborted finals follow the server's exact persistence contract** (`applyAbortContract` in `util/FinalMessages.kt`, applied once in `handleFinal` before anything renders or caches). `abortPersistedServerSide()` mirrors the abort route's 4-way save gate — `earlyAbort`, missing `requestMessage`, empty (already-server-filtered) `content`, or the synthesized `"${userMessageId}_"` response id all mean the server saved no response row. Persisted → the missing `text` is rebuilt via the `parseTextParts` port (TEXT + THINK, the server's literal-space join rule) so the cached row equals what a later fetch returns. Not persisted → the response is DROPPED from the frame: merging it would mint a phantom leaf the next send uses as a `parentMessageId` the server has never heard of. `cacheTurn` is then unconditional (the contract already removed anything unsaved, and the request message of a non-early abort IS persisted — caching it is the only write that persists the optimistic user message). Still suppressed: `saveConversation` (the frame's conversation is a stub with a hardcoded `New Chat` title); the title is re-read network-first instead of generated (an `immediate`-mode title that finished before the Stop exists server-side while the cache holds the placeholder) — retried past a fetched `New Chat` and never applied as a downgrade, because the server's title save is gated on the request's unwind and an immediate read races it (the same emit-then-persist race, one layer up). Both `parseTextParts` and the gate are MIRRORED SERVER LOGIC — re-verify on upstream bumps (`/sync-upstream`)
- **earlyAbort = un-send.** A Stop that lands before `created` persisted nothing — not even the user message — so `handleFinal` removes the turn's minted optimistic message (`MessageTreeDelegate.unsendOptimisticTurn`, single atomic emission) and restores its text to the composer via the `restoreUnsentInput` callback (web parity). The minted id is threaded per-turn through `beginStreaming(isEdit, optimisticUserMessageId)`; it is NULL for regenerate/continue/edit-AI, whose user message is a persisted row the un-send must never remove
- **The final-frame merge is monotonic** (`Message.mergedOver` in `util/TemporaryChatMerge.kt`): applying a frame message over an in-memory copy gap-fills — incoming wins where it carries information, absent/blank keeps local, terminal-state booleans (`error`/`unfinished`) always incoming. This is why a skeletal aborted `requestMessage` no longer needs per-field guards to keep the optimistic message's attachments. ONLY valid at the final-frame chokepoint — full-record sync paths (`getMessages`, `refreshMessages`) keep meaningful nulls and must never use it. `finalizeChatDisplay` returns the POST-merge instances so `cacheTurn` writes exactly what the screen shows
- `onPause()`/`onResume()` handle app backgrounding: checks stream status, resumes if still active. Two abort-window rules: `onPause` does NOT cancel the collector while a Stop is pending (it is carrying the aborted final that holds the partial; the watchdog covers a frame that never arrives), and `onResume` touches nothing when the session already ended while backgrounded (the old wipe of `streamingContent` here was the stop-then-background partial loss)

## Key Components
| Component | Purpose |
|-----------|---------|
| `ChatInput` | Auto-growing text field, send/stop button, file attach, voice |
| `MessageBubble` | Icon + content column + action bar + sibling nav |
| `MessageList` | LazyColumn of MessageBubbles with scroll-to-bottom FAB |
| `ModelSelectorSheet` | Bottom sheet: search + endpoint-grouped model list |
| `ModelSelectorButton` | Header chip showing current model |
| `SiblingNavigator` | "1/3" with chevrons for branch switching |
| `LandingContent` | Greeting, entity icon, conversation starters |
| `StreamingIndicator` | Blinking cursor during active generation |
| `PresetPicker` | Preset selection menu |
| `SavePresetDialog` | Dialog to name and save current config as preset |

## Prompts
- `PromptsLibraryScreen` and `PromptDetailScreen` live in `chat/prompts/`
- `PromptsViewModel` loads prompt groups from `PromptRepository`
- `handlePromptMention()` on ChatViewModel inserts prompt command text at `@` position

## Chat Tools
- Tool state tracked as `Set<String>` in `ChatUiState.enabledTools`
- Tool toggles (web search, code interpreter, file search) and MCP servers live in the paged
  "+" options sheet (`ChatToolsSheetContent`), reached from `ChatInput`'s `onOpenTools`

## Content Cards
- `ImageGenCard` renders DALL-E / image generation results (spinner while generating, AsyncImage when done)
- `LogContentCard` renders expandable log output blocks with monospace text
- Both dispatched from `ContentPartRenderer` — tool calls with name containing `dall` route to ImageGenCard

## Artifacts
- `ArtifactDetector` parses remark-directive format `:::artifact{identifier="id" type="..." title="..."}` with backtick-fenced content; `groupArtifactVersions()` groups by identifier
- Supported types: `text/html`, `image/svg+xml`, `application/vnd.react`, `application/vnd.mermaid`, `text/markdown`/`text/md`, `text/plain`, `application/vnd.code-html`
- `MermaidWebContent` renders Mermaid diagrams via CDN mermaid.js with zoom controls and dark theme
- `MarkdownWebContent` renders Markdown via CDN marked.js + highlight.js with GFM and syntax highlighting
- HTML/React/SVG templates include Tailwind CDN, theme CSS vars, and error handling
- React artifacts compile in-browser (Babel) and load as a real ES module; the artifact's `import`/`export` run verbatim against a generated import map that resolves every bare package via an ESM CDN (no source rewriting, no per-library handling)
- `ArtifactPanel` supports fullscreen Dialog mode, version switching, loading indicator, and WebView error overlay
- `ArtifactButton` shows type-specific icons and subtitle (e.g. "Mermaid Diagram", "React Component")
- `ContentPartRenderer` wires `groupArtifactVersions()` to pass version lists to ArtifactButton/ArtifactPanel
- `ArtifactVersionNav` provides prev/next arrows with "v2/3" indicator
- `ArtifactDownloadHelper` shares artifacts via FileProvider temp file + system share sheet. Maps 25+ language extensions including `.mmd` for Mermaid. Sanitizes filenames to 100 chars
- **Gotcha**: FileProvider authority must match app's declared authority in AndroidManifest

## Media Players
- `VideoContentPlayer` uses ExoPlayer (media3) — 16:9 aspect ratio Card, lifecycle-aware release
- `AudioContentPlayer` uses MediaPlayer — play/pause + seekbar, 250ms polling for progress
- `AudioContentPlayerFromBytes` variant writes bytes to temp file for MediaPlayer
- **Gotcha**: Both players must release resources on dispose — use `DisposableEffect`

## Feedback Comment Dialog
- Thumbs-down opens `FeedbackCommentDialog` before submitting; thumbs-up fires immediately
- Comment is optional — empty string is a valid submission
- Toggling off an existing thumbs-down (already selected) calls `onFeedback(null)` directly, skipping the dialog

## Message Timestamps
- `MessageTimestamp` shows relative/absolute time, toggled on tap
- Parses multiple ISO 8601 format variants; falls back gracefully on invalid input
- Integrated into `MessageBubble` action row
