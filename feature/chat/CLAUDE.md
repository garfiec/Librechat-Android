# feature:chat

## Screen States
`ChatScreenState` enum: `LANDING` | `LOADING` | `ACTIVE`
- **Landing**: no conversation selected, shows greeting + model icon + optional conversation starters
- **Loading**: spinner while fetching messages for a conversation
- **Active**: message list + streaming content + input area

## Navigation
- Sealed interface: `ChatRoute : NavKey` with typed route classes
- Routes: `NewChat` (landing), `Chat(conversationId: String? = null)`, `PromptsLibrary`, `PromptEditor(groupId: String? = null)` (all `@Serializable`)
- Feature entries registered via `EntryProviderScope<NavKey>.chatEntries()`
- When a new conversation starts from the landing page, `pendingNavigationConversationId` triggers navigation to `Chat(id)` after the first stream completes (data persisted to Room), keeping `NewChat` in the back stack
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
- This is a one-shot rebuild — there is no stored anchor field. The truncated `displayMessages` just persists in state for the stream's duration, and `loadConversation` rebuilds the real (untruncated) path on Final. The full tree stays in `messages`/DB, so the old branch remains reachable via sibling navigation.
- **Invariant (load-bearing):** while streaming, nothing may write to Room or mutate `activeBranches` — otherwise the `loadConversation` observer re-emits and rebuilds `displayMessages` with no `streamingLeafId`, un-truncating the path and clobbering the in-place view. The send paths uphold this (no mid-stream Room write); `switchBranch`, `editMessage`, and `regenerateMessage` are all gated on `!isStreaming` so user taps can't break it either.
- **Optimistic id reconciliation:** `doSendMessage` / `editUserMessage` mint the optimistic user message's id and send it as `ChatRequest.messageId` (the `userMessageId` arg on `startChat`), so the server adopts it and echoes it in the Final `requestMessage`. This is what lets the optimistic message reconcile by id — for normal chats on the Room reload, and for temp chats via `mergeFinalMessagesInMemory` (in-memory, never touches Room). Mirrors the web client's top-level `messageId`; without it the optimistic message would linger as a phantom sibling in temp chats. Regenerate/continue/edit-AI send no `messageId` (they create no new user message).
- `editAiMessage` resubmits the parent user turn (regenerate + `isEdited`); it does **not** persist the typed assistant edit (that's `saveEditOnly` → `updateMessageText`, the web `updateMessage` analog). Web additionally seeds the placeholder with the edited content for a transient preview — not ported.

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
- `stopGeneration()` cancels stream job and calls `chatRepository.abortChat()`
- `onPause()`/`onResume()` handle app backgrounding: checks stream status, resumes if still active

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

## Chat Input Toolbar
- `ChatInputToolbar` renders toggles for enabled tools (web search, code interpreter, file search)
- Tool state tracked as `Set<String>` in `ChatUiState.enabledTools`
- `ToolsDropdownMenu` provides overflow for additional tool options
- Toolbar wired into `ChatInput` above the text field

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
