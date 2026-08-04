# feature:chat

## State Architecture (ChatUiState slices + narrowed handles)
`ChatUiState` is decomposed into **17 `@Immutable` sub-state slices**, plus two top-level fields: `error`
(a shared transient-banner channel) and `mediaPreview`. The slices: `conversation`, `content`
(message tree + all streaming fields),
`editing`, `composer`, `selection` (endpoint/model/tools/params), `queue`, `steer`, `search`,
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
- **Loading**: full-screen spinner — *nothing is cached* and the message fetch has not settled
- **Active**: message list + streaming content + input area

### Cache-first open (#300)
`loadConversation(conversationId, cacheFirst)` picks the ordering between the Room read-through
and the server fetch. `cacheFirst = true` subscribes the observer first and revalidates in a child
coroutine, so an **open** paints the cached copy immediately instead of holding the spinner
through a GET that is retried twice with exponential backoff before it can fail. `ChatViewModel.init`
is the only opt-in; every other caller stays network-first **deliberately**. Each of them reloads
precisely because the server holds something the cache does not — a just-finalized turn
(`SendCompletionDelegate`), a just-created branch (`ComparisonModeDelegate.branchFromComparison`),
a stream that ended server-side (`StreamingManagerDelegate`'s `StreamError` / `ResumeExpired` /
`attemptNetworkRecovery`, plus the `launchStream` safety net), or an explicit refresh
(`refreshMessages`) — so a cache emission there serves a snapshot that predates the very thing
being fetched. After a Final that is also the completion flash: the finalized turn is in memory
and Room stays stale until `cacheMessages` lands, so painting the cache re-renders the pre-Final tree.

Two consequences worth knowing before touching that block:
- The fetch's settle is a **`combine` input**, not a flag read inside `collect`. A conversation
  with genuinely zero messages upserts nothing, so Room never emits again — without the
  re-emission the screen spins forever. The comparison auto-rehydrate latch is gated on the same
  input so it still burns on the *authoritative* tail, not a stale cached one.
- `isRefreshingMessages` now means "a messages fetch is in flight" (pull-to-refresh **or** a
  background revalidate) and drives `MessageList`'s pull-to-refresh indicator, which animates off
  that boolean with no gesture. `refreshMessages` early-returns while it is set, which is what
  keeps the two paths from racing each other's clear — and which means a pull issued during the
  open's revalidate is dropped rather than queued, since a fetch is already running.

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
  - `tool_call` -> `StreamingToolCallCard` (expandable, shows args/output), except `ask_user_question`
    (see below)
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
- **The live bubble renders through `TextContentPart(streaming = true)`** (#302), so artifact
  detection runs on every flush — an unclosed artifact streams as its source via
  `IncompleteArtifact` instead of raw `:::artifact{…}` text. The `streaming` flag must stay
  threaded down to every `MarkdownContent` and `CodeBlock` beneath it: dropping it reintroduces
  the per-delta Loading flash + LRU pollution documented in `CachedMarkdown`'s KDoc (the #169
  class), and `CodeBlock`'s off-main highlight retention keys off it. The bubble passes no
  `searchQuery` — the streaming message is outside the search enumeration, which is what keeps
  the two search walks lockstep-free here.
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

## `ask_user_question`: one question, two cards (v0.8.8)

A clarifying question reaches the screen twice over its life, and the two must never overlap.

- **While the run is paused** it is the interactive `PendingActionCard` — the only thing that can
  resolve it. The same question is *also* sitting in `activeToolCalls`, because the agent called a
  tool to ask it; `withoutUnansweredQuestions()` drops it from both streaming lists so the user is
  not asked the same thing twice, once under a spinner for a "call" that cannot finish until they
  answer.
- **Once answered** the same tool call renders as `AskUserQuestionRecordCard` — the durable record
  of the exchange, collapsed to question + answer and expanding to the description and the options
  offered, with the picked ones marked. `ToolCallDispatcher` routes the persisted part there too,
  so history, reload and a mid-run reconnect all show the record rather than the generic card's
  tool name over a JSON dump.

Everything the record shows is already on the wire: the question, its description, options and
`multiSelect` come from the call's own `args` (an object mid-stream, a JSON *string* once
persisted — `parseAskUserQuestion` takes both), and the answer from its `output`. Nothing is
gathered or stashed client-side to make it work, so a conversation opened on a second device
renders identically.

The answer is shown by option **label** only when every segment of it maps back to an option
(`askAnswerDisplay`) — a value may legally contain the `", "` that joins a multi-select answer, so
a partial mapping could split one value into fragments and relabel them as choices the user never
made. Skip posts upstream's `ASK_USER_DECLINED_ANSWER` sentinel (the run must resume either way),
which reads back as "skipped" rather than as a sentence the user typed. A call whose args failed
schema validation carries `inputValidationError` and says so — it was never put to the user, so
rendering it as unanswered would be a lie.

## During-run send: queue vs steer (v0.8.8)
Two different things can happen when the user sends while a reply is generating, and they are not
interchangeable.
- **Queue** (`MessageQueueDelegate`, `QueueState`) — the message becomes the *next turn*, drained FIFO
  when the run ends. Works against every supported server; the long-standing mobile behaviour and the
  default.
- **Steer** (`SteeringDelegate`, `SteerState`) — the message goes into the reply *being written*, injected
  at the run's next tool boundary and announced back as `on_steer_applied`. Needs a server with
  `POST /api/agents/chat/steer` (`FeatureGatesState.steeringSupported`, a date gate — see VERSION_GATES.md).

`ChatUiState.effectiveDuringRunAction` resolves the user's Settings preference against
`canSteerNow` (existing conversation, not comparison mode, steer route present, no live HITL pause). The
composer never makes that call itself: its send button routes to `ChatViewModel.sendDuringRun()`, and the
picker beside it (`DuringRunSendMenu`, rendered only when both routes are open) calls `steerMessage()` /
`queueMessage()` explicitly.

**The button's face and its action must come from the same value.** `sendDuringRun()` switches on
`ChatUiState.duringRunSendTarget`, so the button's icon/label does too (`sendButtonModeFor`, extracted from
the composable purely so this module — which has no Compose test harness — can assert the mapping). Deriving
the face from the *preference* instead is a bug that nothing catches: a live `ask_user_question` pause
overrides the preference, so the button read "add to queue" over a tap that answered the question. Behaviour
correct, label lying. `duringRunAction` in `ChatInputState` is now the picker's checkmark only.

**Invariant: no steer path may lose the user's text, duplicate it, or send text the user withdrew.**
Every rejection code, transport failure, and lost race re-homes the message into the follow-up queue —
*always* the queue, never the live-send path, because `runWhenSendReady` is allowed to REFUSE (no model
selected, readiness timeout) and a degraded steer has no composer left to put the text back into.
`enqueueSpec` self-drains once the run is over, so an ended run still sends immediately. This is why the
rejection codes are diagnostic only; there is no `SteerFallback` branch.

A steer carries the `QueuedMessage` spec it *would* have become, minted at send time: rebuilding one at
failure time would capture whatever model, tools, and attachments the composer holds seconds later.

Three server reports hand back un-injected steers **claim-on-read** (the `final` frame, the abort ack,
`/chat/status`'s `unrecoveredSteers`) — parsing one and ignoring it destroys the words. That obligation is
**structural, not conventional**: `ChatRepository.checkStreamStatus` / `abortChat` take a required
`claimSteers` lambda and invoke it before returning, emptying the field on the value they return, so no
guard or early `return` at a call site can come between the read and the claim. The final frame's copy is
claimed at the event-dispatch site, outside `handleFinal`, whose early returns would otherwise skip it.
The same steer rides more than one report (a Stop gets the ack AND the aborted final), so re-homing is
deduped by steer id. A stream that dies on an error carries no report, so `reclaimLocalChips()` converts
the locally-held records instead; it deliberately does NOT run on `Finalized`, where the frame's own list
is authoritative and converting again would double-send any steer whose applied event was missed.

**`SteeringDelegate` keeps ONE record per steer** (`records: Map<id, SteerRecord>`, status
`SENDING | PENDING | CANCELLED | APPLIED | RECLAIMED`); `SteerState.pendingSteers` is a derived view of the
live ones. `clear()` is a **display** boundary, not a memory one — it clears the chips and KEEPS the
records:
- a mid-run reconnect runs `clear()` (`resumeStream` → `startStreamSession`) and the sync frame re-seeds
  the same steers by server id; dropping the records would strand their specs and the re-homed message
  would be rebuilt from the composer's current selection — the exact failure the specs exist to prevent;
- an `APPLIED` record is what stops a late 202 from re-homing text already in the reply;
- a `CANCELLED` record is what stops a stale sync frame from resurrecting a withdrawn steer;
- a `SENDING` record's POST outlives the boundary and needs its spec to degrade.

Settled records are tombstones, bounded at 32; live ones are never evicted. The map is confined to the
handle's Main-dispatched scope — do not add a `withContext` inside the delegate, and never mutate the map
inside a `handle.update { }` block (that is a `MutableStateFlow.update` CAS loop and may re-run).

Steering is text-only on mobile: a during-run send carrying attachments is routed to the queue, where the
existing upload/usage path already handles them.

## Chat Tools
- Tool state tracked as `Set<String>` in `ChatUiState.enabledTools`
- Tool toggles (web search, code interpreter, file search) and MCP servers live in the paged
  "+" options sheet (`ChatToolsSheetContent`), reached from `ChatInput`'s `onOpenTools`

## Content Cards
- `ImageGenCard` renders DALL-E / image generation results (spinner while generating, AsyncImage when done)
- `LogContentCard` renders expandable log output blocks with monospace text
- Both dispatched from `ContentPartRenderer` — tool calls with name containing `dall` route to ImageGenCard

## Artifacts
- `ArtifactDetector` is a **line scanner**, not a regex — ported in shape from upstream's
  `findArtifactClose`/`getOpeningCodeFence` (`packages/api/src/artifacts/update.ts`), because the real
  contract is "whatever micromark parses", not a pattern. It accepts backtick **and** tilde fences of
  any length ≥3 (closing fence `{n,}`, i.e. *at least* as long), unfenced content, leaf directives
  (`::artifact{…}`), CRLF, `[label]` between name and attributes, and quoted/unquoted/valueless
  attributes. `groupArtifactVersions()` groups by identifier.
- **Three divergences from the web client are deliberate** — do not "fix" them: `::: trailing` closes
  the artifact here (web swallows the rest of the message; upstream's own two implementations
  disagree), a tab-indented content fence renders here (web emits garbage), and attribute-less
  `:::artifact` produces nothing (web paints no card either — `updateArtifact` early-returns on the
  all-defaults key). A 25-case differential corpus pins all of this in `ArtifactDetectorCorpusTest`:
  **22/25 agreement, divergent set exactly {10, 23, 25}**. Any *other* row diverging is a regression.
- **`Artifact.isComplete`** is false when the closing `:::` never arrived — truncated mid-write, or
  still streaming. Incomplete artifacts render their **source** via `IncompleteArtifact` → `CodeBlock`,
  bypassing the inline prefs entirely, and they *do* count for in-conversation search. Both rules are
  load-bearing: every inline pref defaults to off, so the normal path would collapse them to a button
  and take partial content off screen, and half-written markup in a WebView is a blank box. An
  unclosed directive with **no** opening fence is *not* emitted at all — that's a prose mention, and
  emitting it would swallow the rest of the reply.
- **While streaming, complete artifacts are gated to `ArtifactButton`** even when an inline pref is
  on (`shouldRenderInlineArtifact`): the segment subtree recomposes per delta, and an inline preview
  reloads its WebView on every content change (mermaid recreates its view outright). The real
  preview mounts once, at settle, where the streaming→final swap replaces the subtree anyway.
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

## Feedback: rating + reason tag + comment
- **Both** thumbs open `FeedbackTagSheet` — the route validates against an object schema whose
  `tag` is REQUIRED, so a bare rating is rejected 400 and there is no one-tap path. Tapping the
  already-filled thumb clears instead (`onFeedback(null)` → `{}`, which the route's `feedback ==
  null` guard reads as a clear).
- The wire shape is `MinimalFeedback` (`{rating, tag, text?}`), deliberately a different type from
  the read/persist model `Feedback`: neither `rating` nor `tag` carries a default, so
  `encodeDefaults = false` cannot drop them from the body. `Feedback.rating` *does* default (to
  `FeedbackRating.UNKNOWN`) so `coerceInputValues` absorbs a rating from a newer server — it rides
  on `Message`, and a throw there fails the whole `GET /messages` decode.
- `Feedback.tag` stays a raw `JsonElement`: the validated route persists a bare key string, but
  rows written before it can hold the full tag object.
- The Room write goes through `feedbackColumnValue` in `MessageMapper`, beside the decode that
  reads it. The write used to store a bare rating string into a column that file round-trips as
  `Feedback` JSON, so every read threw into a catch that returned null and the thumb emptied
  itself on the next emission.
- `submitFeedback` is gated on `!isStreaming` like `switchBranch`/`editMessage`/`regenerateMessage`
  — it caches to Room, which would re-emit through the `loadConversation` observer and un-truncate
  the streaming anchor. **The affordance is gated too** (`LocalFeedbackEnabled`, provided by
  `MessageList`): the thumbs are *disabled* — not hidden, which would reflow the action row —
  while streaming. Both are required. A sink-only guard sits at the end of a multi-step flow, so
  the user would pick a reason and type a comment before anything refused; an affordance-only gate
  would leave the Room write unprotected against the next caller. `FeedbackTagSheet` reads the same
  local and disables Submit if a run starts while the sheet is already open (`onResume` adopting
  another client's run, a queued message draining) — the sheet stays up and keeps the draft rather
  than dismissing, so Submit re-enables when the run ends. Note the sibling mutations
  (`switchBranch`, `editMessage`, `regenerateMessage`) do NOT gate their affordances — their
  arrows and buttons stay live mid-stream and silently no-op.
- The sheet is a `ModalBottomSheet` of radio rows, not chips or an `AlertDialog`: `FilterChip`/
  `InputChip` hardcode `Role.Checkbox` over any caller-supplied role, and `AlertDialog`'s text slot
  has no scroll modifier, so eleven reasons plus a comment field clip out of reach.

## Activity groups, steers, and content segmentation (v0.8.8)
`groupContentParts` (`util/ContentSegments.kt`) is ONE pure transform producing both the steer
segmentation and the activity grouping, memoized at `MessageContentAndActions`. Two passes over the
same list would eventually disagree about a boundary and render the user's words inside a collapsed
tool block. It is pure because this module has no Compose test harness.

- **Activity groups.** Consecutive reasoning + tool calls fold under one header, terminated by an
  `activity_label` part. A blank label is a *reservation* — invisible, and it only moves the claim
  boundary so a later filled label cannot reach back past it. With no label at all the block
  re-splits into the legacy shape (reasoning standalone, runs of ≥2 tools grouped), so a server
  without the feature renders exactly as before.
- **Group identity is anchored to the first TOOL CALL, never the first part.** A label absorbs the
  block's leading `THINK` when its text lands, so `parts[0]` flips at the instant the block becomes
  a group; keying on it remounts the group and drops the user's expansion.
- **Auto-collapse is latched once per group id**, and suppressed entirely on the message that just
  took over from the streaming bubble — the live tool cards vanish in that same swap, and folding
  the same calls in the same frame drops the reply's height by the whole stack.
  **The suppressed message is named by the ViewModel, not derived in the UI**
  (`MessagesState.justSettledMessageId` → `MessageList` → `LocalSuppressGroupAutoCollapse`).
  `finalizeChatDisplay` writes it in the SAME atomic update that swaps the message in, so the flag
  is already true the first time the finalized message composes. Both UI-side derivations fail:
  a `LaunchedEffect` on `isStreaming` commits *after* the composition that registered it, so the
  groups have already collapsed and nothing re-opens them; deriving during composition instead
  marks the last message of every *opened* conversation as freshly settled and keeps its groups
  open forever. Only a finalize writes it, which is what makes it a transition.
  **It is deliberately NOT cleared at the turn boundary.** A drain of a non-empty queue re-enters
  `beginStreaming` inline in the *same* Main dispatch as the finalize — nothing on that path
  suspends, because `awaitReplySettled`'s predicate is already true and `viewModelScope.launch`
  on an already-Main dispatcher runs inline — so a clear there lands before Compose ever sees the
  flag. Persisting is safe because the value is a message id: it can only re-match the one message
  it named, and the next finalize overwrites it. `ActivityGroup` latches the suppressed decision
  as well as the collapsing one, so a group cannot simply fold later when the flag moves on.
  A live comparison never reaches `finalizeChatDisplay` (it rebuilds from a background reload), so
  `SendCompletionDelegate` calls `markSettled` on that branch.
  Guarded by `JustSettledMessageTest` (single-emission, mount trap, overwrite-without-clear) plus
  `ChatViewModelDuringRunSendTest`'s drain case, which is the only place the inline-drain
  interleaving is visible. Each assertion was verified to FAIL on the corresponding broken version
  — three earlier attempts at this mechanism compiled and passed every gate while doing nothing.
- **Steers.** A `steer` part renders as a user turn where the words entered the run, and each
  segment resuming after one restates attribution. Attribution follows the agent that had taken
  over when the steer landed; a handoff AT the resume point keeps the pre-handoff author so the
  marker announces the transition itself. Only the id is on the wire for a handed-off agent, so it
  renders under a neutral badge rather than the previous agent's avatar.
- **Per-part collapse state is keyed and saveable** (`stateKey`, threaded through
  `ContentPartRenderer`). It was positional `remember`: grouping shifts children under a wrapper,
  which migrates an expanded thinking block to whichever part now occupies its slot, and lazy-item
  disposal dropped it on scroll.
- Steer text and rendered activity labels are counted by `SearchMatchEnumeration`, so in-conversation
  search can reach them and a collapsed group holding the focused match opens.

## Tool intent labels (v0.8.8)
A tool card's title is the model's own `intent` string when present, else the raw tool name.
**Accepted only when it is the FIRST key of the args object** — the opt-in (capability +
per-tool `describe_intent`) is entirely server-side and unobservable here, so a plain
"is there an `intent` string" test would retitle a user's MCP tool that legitimately takes a
parameter by that name. Upstream injects it at position zero, and that ordering is the only part
of the contract this side can check. Lifted labels are stripped from the expanded args dump.
Shipped ahead of upstream's own presentation, which scopes rendering as a follow-up.

## Message Timestamps
- `MessageTimestamp` shows relative/absolute time, toggled on tap
- Parses multiple ISO 8601 format variants; falls back gracefully on invalid input
- Integrated into `MessageBubble` action row
