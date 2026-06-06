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
- `MessageTree.kt` builds tree via `buildActiveMessagePath(messages, activeBranches)`
- `activeBranches: Map<String, Int>` tracks which sibling is displayed per parent
- `switchBranch(parentMessageId, siblingIndex)` updates branch selection and rebuilds display list
- Editing a user message creates a new sibling (new branch); regenerating an AI message also creates a sibling

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
