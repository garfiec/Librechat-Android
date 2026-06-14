# Modularization Backlog

Tracking the file-size / decomposition initiative. Each row is a planned back-to-back PR.
Goal is not just line count: each pass should also tighten architecture, remove code smells,
and bring the code closer to idiomatic Compose / unidirectional-data-flow conventions.

## Established conventions to follow

- **Delegate pattern** (`feature/chat/.../viewmodel/delegate/`): a delegate is a plain class
  constructed with a `ChatStateHandle` (or feature equivalent) + repositories. It mutates shared
  UI state via `stateHandle.update { copy(...) }`, owns transient one-shot signals via its own
  `MutableStateFlow`, and uses `stateHandle.scope` for coroutines. The ViewModel holds it as a
  private val and re-exposes delegate flows through `get()`.
- **No cross-feature deps**: feature modules depend on `:core:*` only.
- **UDF**: UI → ViewModel → Repository. Composables stay dumb; logic moves down, not into the view.
- **Pure helpers** (codecs, mappers, builders) go in plain top-level objects/files, unit-testable
  without Android or coroutines.

## Backlog (priority order)

| # | File | Lines | Type | Status |
|---|------|-------|------|--------|
| 1 | `feature/chat/.../viewmodel/ChatViewModel.kt` | 2151→1068 | VM god-class | **done (awaiting device test)** |
| 2 | `feature/agents/.../viewmodel/AgentEditorViewModel.kt` | 1938 | VM god-class (no delegates yet) | planned |
| 3 | `feature/chat/androidMain/.../screen/ChatScreen.kt` | 1232 | Composable + leaked logic | planned |
| 4 | `feature/agents/.../components/AgentActionsPanel.kt` | 882 | Two 240+ line dialogs | planned |
| 5 | `feature/agents/.../screen/AgentEditorScreen.kt` | 926 | One 467-line Column | planned |
| 6 | `feature/settings/.../viewmodel/SettingsViewModel.kt` | 966 | Partial delegation | planned |
| 7 | `feature/chat/.../components/SharedContentParts.kt` | 711 | Duplicate collapsible cards | planned |

## Explicitly NOT decomposing

- `core/ui/.../EndpointParameterRegistry.kt` (913) — intentional 1:1 mirror of upstream
  `parameterSettings.ts`; extracting a base template adds indirection without cutting lines.
- `core/network/.../SseEventMapper.kt` (560) — dense but one-task-per-function; cohesive.

---

## PR #1 — ChatViewModel

**Shape:** one PR, one commit per delegate, single device-test pass at the end.
**Decision:** extract the deeply-coupled completion logic too (5 delegates, not 4).
**Result:** ChatViewModel 2151 → 1068 lines (−50%). Android + iOS compile, detekt, and the
chat unit-test suite all green. Awaiting device test before push.

The send/stream code is a layered pipeline, so extraction order is dependency-driven
(leaves first, the high-coupling streaming/completion core last):

### Commit 1 — `MessageTreeDelegate` (~80L, LOW risk)
- Moves: `switchBranch`, `anchorStreamTo`, `finalizeTemporaryChatDisplay`, temp-chat toggle + guards.
- Deps: `stateHandle` only (uses pure utils `buildActiveMessagePath`, `mergeFinalMessagesInMemory`).
- Leaf utility the other delegates call. Upholds the streaming-anchor invariant (chat CLAUDE.md).

### Commit 2 — `ComparisonModeDelegate` (~150L, MED risk)
- Moves: dual-stream buffers + `isSecondaryEvent` (relocated **out of** `ModelSelectionDelegate`),
  comparison init in `doSendMessage`, the 3-way branches in `handleStreamEvent`, comparison cleanup
  in error/final/stop, `branchFromComparison`.
- Exposes `routeEvent(event): Boolean` so `handleStreamEvent` collapses to
  `if (comparison.isEnabled && comparison.routeEvent(event)) return`. **This shrinks the dispatch.**
- Deps: `stateHandle`, `modelDelegate` (buildAddedConvo).

### Commit 3 — `SendCompletionDelegate` (~250L, HIGH risk)
- Moves: `handleCreated`, `handleFinal`, title generation (`TitleGenerationGate` exists),
  `applyConversationModel` re-derive, optimistic-id reconciliation, temp-chat-merge dispatch,
  `titleGenerationRequested`, NewChat nav handoff (`selectionHandoff`).
- Deps: `stateHandle`, conversation/draft/message repos, `ttsDelegate`, `modelDelegate`,
  `officePreviewDelegate`, `treeDelegate`, `selectionHandoff`, flags (`isNewConversation`,
  `isHandedOffNewChat`), `loadConversation` callback, final-text supplier.

### Commit 4 — `StreamingManagerDelegate` (~200L, HIGH risk, mechanical after #3)
- Moves: `streamingBuffer`/dirty, `streamJob`/`streamingUpdateJob`/`connectivityJob`,
  `lastErrorWasNetwork`/`wasStreaming`, updater start/stop + flush, `collectStreamSafely`,
  the slim `handleStreamEvent`, connectivity observer (`start`/`cancel`/`attemptNetworkRecovery`),
  `resumeStream`, `resumeActiveStreamIfNeeded`, `onPause`/`onResume` stream parts.
- Deps: `stateHandle`, `chatRepository`, `connectivityObserver`, `comparisonDelegate`,
  `subagentTraceDelegate`, `officePreviewDelegate`, `sendCompletionDelegate` (onCreated/onFinal),
  `userKeyErrors` emitter, `loadConversation` callback.

### Commit 5 — `MessageEditingDelegate` (~200L, MED risk)
- Moves: `editMessage`/`editUserMessage`/`editAiMessage`, `regenerate*`, `continueGeneration*`,
  edit UI state (`startEditing`/`onEditTextChanged`/`cancelEditing`/`submitEdit`/`saveEditOnly`),
  `isEditOrRegenerate`.
- Deps: `stateHandle`, chat/message repos, `streamingDelegate`, `treeDelegate`, `ChatRequestBuilder`.

### Supporting extraction
- `ChatRequestBuilder` (pure helper): `buildEphemeralAgent()` + `currentDispatch()`, shared by the
  VM send path and `MessageEditingDelegate` — avoids cross-delegate calls for request assembly.
  Unit-testable without coroutines/Android.
- `doSendMessage` / `sendMessage` stay in the VM as the thin orchestrator wiring the delegates.

### Cross-cutting cleanups to fold in
- Unify state writes on `stateHandle.update {}` (VM currently mixes raw `_uiState.update {}` with
  delegate `stateHandle.update {}`).
- Replace inline fully-qualified types (`com.garfiec...MermaidRenderCache`, `...InlineArtifactPrefs`)
  with imports.
- `@Suppress("TooManyFunctions", "LongParameterList")` should shrink/drop as logic moves out.

### Load-bearing invariants — DO NOT break (verify each commit)
- Streaming anchor: no Room write / `activeBranches` mutation mid-stream.
- Temp-chat data-at-rest guard (handleFinal / loadConversation / refreshMessages).
- Optimistic-id reconciliation: `userMessageId` echoed in Final `requestMessage`.
- Network recovery: `lastErrorWasNetwork` must propagate through Sync/Created/Final.
- Comparison isolation: dual-stream branches gated on `comparisonState.isEnabled`.
