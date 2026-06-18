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
| 1 | `feature/chat/.../viewmodel/ChatViewModel.kt` | 2151→1068 | VM god-class | **done — PR #157 (merged)** |
| 2 | `feature/agents/.../viewmodel/AgentEditorViewModel.kt` | 1938→518 | VM god-class (no delegates yet) | **done — device-verified** |
| 3 | `feature/chat/androidMain/.../screen/ChatScreen.kt` | 1232→260 | Composable + leaked logic | **done — PR #159 merged** |
| 4 | `feature/agents/.../components/AgentActionsPanel.kt` | 882→185 | Two 240+ line dialogs | **done — PR #160 merged** |
| 5 | `feature/agents/.../screen/AgentEditorScreen.kt` | 926→141 | One 467-line Column | **done — PR #161 merged** |
| 6 | `feature/settings/.../viewmodel/SettingsViewModel.kt` | 966→435 | Partial delegation | **done — device-verified** |
| 7 | `feature/chat/.../components/SharedContentParts.kt` | 711 | Duplicate collapsible cards | planned |

## Explicitly NOT decomposing

- `core/ui/.../EndpointParameterRegistry.kt` (913) — intentional 1:1 mirror of upstream
  `parameterSettings.ts`; extracting a base template adds indirection without cutting lines.
- `core/network/.../SseEventMapper.kt` (560) — dense but one-task-per-function; cohesive.

---

## PR #6 — SettingsViewModel

**Shape:** one PR, one commit per extraction, single device-test pass at the end.
**Result:** SettingsViewModel.kt 966 → 435 lines (−55%). Android + iOS compile and
detektMetadataCommonMain all green.

A `commonMain` VM god-class with **partial** delegation: 5 delegates already existed
on the write side, but the DataStore read flows and the account logic still lived in
the ViewModel. This pass finishes the delegation. The public `SettingsViewModel`
constructor and API are unchanged. Three commits:

1. **`SettingsUiState.kt`** — moves the 160-line `SettingsUiState`, plus
   `SettingsCommand`, `LogsExportPayload`, `DEFAULT_COMMANDS`, and the
   `User.toDisplayData()` mapper (`private` → `internal`) into a sibling state file.
2. **`SettingsPreferencesController.kt`** — extracts the entire DataStore layer: the
   four intermediate preference holders, every `stateIn` flow, the multi-stage
   `combine` that merges them with the imperative state into `uiState`, and the
   matching write setters (theme / chat / tablet / inline-artifact / language). One
   object now owns both the read flows and the writes; the ViewModel forwards.
3. **`AccountDelegate.kt`** — extracts the account section (profile load with the
   cancel-on-retry job, avatar upload, balance, sign-out, and account deletion with
   the 403 → OTP step-up) into a `SettingsStateHandle`-based delegate matching the
   existing delegate pattern.

`SettingsViewModel.kt` now holds only delegate/controller construction, `init`, the
role / permission / account-deletion observers, the small dialog-toggle state
(language / fork / commands / personalization), and the thin delegate forwarders.

---

## PR #5 — AgentEditorScreen

**Shape:** one PR, one commit per extraction, single device-test pass at the end.
**Result:** AgentEditorScreen.kt 926 → 141 lines (−85%). Android + iOS compile and
detektMetadataCommonMain all green.

A `commonMain` (shared Android + iOS) Compose decomposition. The public
`AgentEditorScreen` signature is unchanged, so the `AgentsNavigation` create/edit
call sites are unaffected. Four sibling files in the same `screen/` package,
`private` → `internal` where a symbol crosses a file:

1. **`AgentEditorTopBar.kt`** — the `TopAppBar` with the edit-mode overflow menu
   (duplicate / version history / delete). Takes plain callbacks, not the ViewModel.
2. **`AgentEditorDialogs.kt`** — the modal layer: delete/duplicate confirmations,
   version history, tool selection, and the Code Interpreter API-key dialog, each
   gated on its own state flag.
3. **`AgentEditorComponents.kt`** — `InstructionsField` (insert-variable caret menu)
   and `SelectedToolRow`, the two shared helper composables.
4. **`AgentEditorForm.kt`** — the scrollable editor body (avatar → save), the bulk of
   the file. Receives the editor ViewModel for its field callbacks plus file-add /
   show-tool-dialog lambdas, so the file pickers stay in the screen shell.

`AgentEditorScreen.kt` now holds only the shell: state, the three file pickers, the
error-marker and event `LaunchedEffect`s, and the `Scaffold` wiring. The screen owns
the editor ViewModel and forwards it to its own private children, so it carries a
localized `@Suppress("ViewModelForwarding")` (these are screen-internal, not the
reusable-component forwarding the rule targets).

---

## PR #4 — AgentActionsPanel

**Shape:** one PR, one commit per extraction, single device-test pass at the end.
**Result:** AgentActionsPanel.kt 882 → 185 lines (−79%). Android + iOS compile and
detektMetadataCommonMain all green.

A `commonMain` (shared Android + iOS) Compose decomposition. The public
`AgentActionsPanel` signature is unchanged, so `AgentEditorScreen` is unaffected.
Two sibling files in the same `components/` package, `private` → `internal` where a
symbol crosses a file:

1. **`ActionEditorDialog.kt`** — the full-screen OpenAPI action editor (`Dialog` +
   `Scaffold`, debounced spec validation, save-metadata assembly) plus its
   `AuthenticationSection` and `FunctionTable` helpers.
2. **`ActionAuthConfigDialog.kt`** — the auth-configuration `AlertDialog`
   (none / API-key / OAuth) plus its `AuthTypeRadioOption` helper and the shared
   `HIDDEN_PLACEHOLDER` constant.

`AgentActionsPanel.kt` now holds only the collapsible panel shell and its
per-action `ActionCard`.

---

## PR #3 — ChatScreen

**Shape:** one PR, one commit per extraction, single device-test pass at the end.
**Result:** ChatScreen.kt 1232 → 260 lines (−79%). Android + iOS compile, detekt +
detektMetadataCommonMain + `:app:lint`, and the chat unit-test suite all green.
Device-verified on the Pixel 10 Pro Fold emulator (landing, send + streamed reply,
top-bar overflow menu, model selector — all render with no regression; the
pre-existing cold-start ANR from issue #93 is unrelated to this UI-only split).

This is an Android-only Compose decomposition (the iOS `ChatScreen` actual lives
separately in `PlatformScreens.ios.kt` and is untouched). The public `ChatScreen`
signature is unchanged, so `NewChatScreen` / `ChatNavigation` callers are unaffected.
Five sibling files in the same `screen/` package, `private` → `internal` where a
symbol crosses a file:

1. **`ChatSpeechToText.kt`** — `rememberChatStartRecording()`: the device/server STT
   launchers, the RECORD_AUDIO permission flow, and the engine/language mapping
   helpers. Moves the leaked Android intent plumbing out of the view.
2. **`ChatScreenEffects.kt`** — the screen's one-shot side effects (new-conversation
   nav handoff, error/share-link snackbars, fork/duplicate nav, stream resume on
   foreground, provider-key error snackbar, back-nav after delete/archive).
3. **`ChatContent.kt`** — `ColumnScope.ChatContent`: the per-state content area
   (landing / loading / active), including the comparison dual-pane (tablet) and
   tab-pager (phone) layouts and `buildComparisonDisplayMessages`.
4. **`ChatScreenDialogs.kt`** — the dialogs/sheets (preset load/save, fork options,
   model parameters, rename/delete confirmations, primary + secondary model
   selectors) plus the relocated `ChatRenameDialog` / `ChatDeleteConfirmationDialog`.
5. **`ChatTopBar.kt`** — the existing `ChatTopBar` composable relocated unchanged.

`ChatScreen.kt` is now a thin scaffold wiring the top bar, content, effects, dialogs,
and composer. Local-only sheet visibility (preset picker, save-preset, secondary
model sheet) is hoisted to the caller via boolean flags + setter lambdas.

---

## PR #2 — AgentEditorViewModel

**Shape:** one PR, one commit per extraction, single device-test pass at the end.
**Result:** AgentEditorViewModel 1938 → 518 lines (−73%). Android + iOS compile, detekt +
detektMetadataCommonMain + `:app:lint`, and the agents unit-test suite all green. Device-verified
on the Pixel 10 Pro Fold emulator (full create/load/edit/save/delete lifecycle, all editor sections,
conditional capability UI, model selector — all pass).

The VM had no delegate structure yet, so the first step introduced the shared accessor and the
extraction order is leaf-first (the loader depends on the files delegate's re-merge; save depends
on the files delegate's cache reset):

1. **`AgentEditorMappers.kt`** (pure top-level) — `partitionTools`, `applyAgentData`, the
   model-parameter + handoff-edge codecs, `buildToolsList`, and the display-data mappers. Unit-
   testable without a ViewModel/coroutines/Android.
2. **`AgentEditorStateHandle`** + **`AgentFilesDelegate`** — shared state accessor (mirrors
   `ChatStateHandle`) introduced with its first consumer; file slots (upload/remove with
   `tool_resource` routing), avatar, and the file-metadata enrichment cache
   (`remergeLoadedFiles` / `resetFileCache`).
3. **`AgentLoaderDelegate`** — `loadAgent` (+ post-load re-merge) and the reference-data fetches.
4. **`AgentCapabilitiesDelegate`** — availability observers (code/web/skills/subagents + version-
   gated collaborative/handoffs/ACL) and the skill/subagent/chain/handoff section handlers.
5. **`CodeToolAuthDelegate`** — Code Interpreter tool-key verify/install/revoke + the auth-gated toggle.
6. **`AgentActionsDelegate`** — OpenAPI action load/save/delete.
7. **`AgentSaveDelegate`** — validation + create/update, duplicate/delete/revert, event emission.

`AgentEditorViewModel` is now a thin orchestrator wiring six delegates over the shared handle;
its public method surface is unchanged (screen + Koin graph untouched). Screen-facing companion
consts (`AGENT_FILES_*`, `MAX_SUBAGENTS`, `CHAIN_MAX`) deliberately stay on the VM.

---

## PR #1 — ChatViewModel

**Shape:** one PR, one commit per delegate, single device-test pass at the end.
**Decision:** extract the deeply-coupled completion logic too (5 delegates, not 4).
**Result:** ChatViewModel 2151 → 1068 lines (−50%). Android + iOS compile, detekt, and the
chat unit-test suite all green. Shipped as PR #157 (merged).

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
