# core:ui

Material 3 theme and shared Compose components used across all feature modules. Purely presentational -- no business logic, no ViewModels, no repositories.

## What This Module Provides

### Theme (`theme/`)
- `Theme.kt`: `LibreChatTheme` wrapping Material 3 `MaterialTheme`. Maps LibreChat web colors to M3 color roles.
- `Color.kt`: Color palette derived from the web app's CSS variables.
- `Type.kt`: Typography scale.
- `Shape.kt`: Corner radius definitions.

### Shared Components (`components/`)
- `LibreChatTopBar` - App bar with optional back navigation and actions.
- `LoadingIndicator` - Centered circular progress.
- `ErrorBanner` - Dismissible error message bar.
- `EmptyState` - Illustration + message for empty lists.
- `AvatarImage` - User/agent/model avatar with Coil image loading and fallback.
- `ConfirmationDialog` - Reusable confirm/cancel dialog.
- `ModelIcon` - Endpoint-specific model icons.
- `EndpointBadge` - Colored badge showing the AI provider.
- `SearchBar` - Reusable search input field.
- `BottomSheetScaffold` - Wrapper for modal bottom sheets.
- `PullToRefresh` - Pull-to-refresh wrapper.
- `BannerDisplay` - Dismissible server banner cards (info/warning/error types).

### Markdown
- core/ui does NOT provide a shared markdown renderer. Features render markdown
  directly with the `com.mikepenz` multiplatform-markdown-renderer
  (`libs.markdown.renderer.m3`) — see `feature/chat` (CachedMarkdown, streaming-
  tuned) and `feature/skills` (plain static `Markdown(...)`). `feature/chat`
  also has its own WebView-based artifact/LaTeX rendering. If a genuinely shared
  renderer is ever needed, lift it here; until then there is nothing in core/ui
  to depend on.

### Message Components (`message/`)
- `MessageBubble` - Shared message rendering used by `:feature:chat`.
- `ToolCallCard` - Expandable tool call display.
- `FileAttachmentChip` - File reference chip.
- `FeedbackButtons` - Thumbs up/down.

### PDF (`pdf/`, androidMain only)
- `PdfDocumentHolder` - Owns the `PdfRenderer`/fd; mutex-serialized on-demand `renderPage` with
  dimension caps and a page-count bound. Created from bytes, closed by the owning composable.
- `PdfPageContent` - One page rendered when it scrolls into a LazyColumn window and recycled when
  it scrolls out. Shared by `:feature:chat`'s viewer (adds per-page pinch-zoom via the modifier
  slot) and `:feature:files`' preview (adds page labels). Fix render/recycle logic HERE, not in the
  feature copies — there are none.
- `StreamingIndicator` - Pulsing indicator during SSE streaming.

## Rules

- **No business logic.** No ViewModels, no repository calls, no use cases.
- Components accept domain models from `:core:model` as parameters and render them.
- All components must be stateless or hoist state to the caller.
- Dependencies: `:core:model`, `:core:common`, Coil for image loading, Compose libraries, Kermit logging (androidMain, for the PDF holder).
- Convention plugins: `librechat.mobile.library` + `librechat.mobile.compose`.
- No DI in this module (no Koin modules, no injected classes).
- Use `@Preview` annotations on all components for Android Studio preview support.
- During SSE streaming, buffer markdown re-renders to ~100ms intervals to avoid frame drops.

### Dynamic Parameters (`components/Dynamic*.kt`)
- `DynamicParameterPanel` dispatches `List<ParameterDefinition>` to typed controls (slider/dropdown/checkbox/input/textarea)
- `ParameterDefinition` contains: key, type, label, description, default, min, max, step, options
- `DynamicSlider` snaps to step increments; calculates stepCount from range
- All controls are stateless — caller manages values via `Map<String, String>`
- `ModelParameterContent` falls back to existing fixed params when no schema available
- **Gotcha**: Slider step count calculated as `((max - min) / step).toInt()` — ensure step divides range evenly to avoid drift

## Compose Performance Rules

These rules apply to ALL composables across feature modules, not just core:ui.

### UI State Architecture

1. **Single UI state data class per screen.** Each ViewModel exposes ONE `StateFlow<XyzUiState>` — never 5+ individual StateFlows that each trigger recomposition independently.

2. **UI state contains only display-ready data.** The ViewModel maps domain models (e.g., `Conversation` with 28 fields) to minimal display data classes (e.g., `DrawerConversationDisplayData` with 7 fields). Composables should never receive full domain models.

3. **Mark UI state classes `@Immutable`.** This lets the Compose compiler skip recomposition when the reference hasn't changed. All fields must be `val` with stable types.

4. **Collect state at the narrowest scope.** If only `DrawerContent` uses drawer state, collect inside `DrawerContent` — not in the parent `PhoneLayout` which also owns the NavDisplay. State changes should only recompose the composable that reads them.

5. **Use `SharingStarted.Eagerly`** for UI state that should be ready before the composable subscribes (avoids empty→populated two-phase render jank on first frame).

### Stability

6. **All types passed to composables must be stable.** Primitives, `String`, `@Immutable` data classes, and enums are stable. Standard `List`/`Set`/`Map` are NOT stable by default — they are declared stable in `compose-stability.conf` at the project root.

7. **Never pass lambdas that capture unstable references.** Prefer method references (`viewModel::doThing`) or `remember`'d lambdas over inline lambdas that capture changing state.

### LazyColumn / LazyList

8. **Always provide `key`** on `items()` calls. Keys must be unique, stable identifiers (e.g., `conversationId`).

9. **Always provide `contentType`** when a LazyColumn has mixed item types (headers, content rows, loading indicators). This enables Compose to reuse compositions across items of the same type.

10. **One element per `item {}` block.** Multiple composables in one block prevents independent recycling.

11. **No nested same-direction scrollables.** Never put `LazyColumn` inside `verticalScroll` — combine into one `LazyColumn` using `item {}` blocks.

### Avoiding Unnecessary Work

12. **Move data transformations to the ViewModel.** Sorting, filtering, grouping, and model mapping happen in the ViewModel — not in `remember` blocks in composables.

13. **Cache expensive objects as top-level constants.** `RoundedCornerShape`, `Regex`, `DateTimeFormatter` — allocating these per-item per-frame is wasteful.

14. **Use `background(color, shape)` instead of `clip(shape).background(color)`.** The `clip` modifier creates a persistent clip layer per composable; `background` with a shape parameter draws the clipped background without the layer overhead.

15. **Avoid `copy(alpha = ...)` on colors.** Alpha-blended colors force GPU compositing. Use opaque colors from the theme where possible.

16. **Defer state reads to later phases.** Use lambda-based modifiers (`Modifier.offset { }`, `Modifier.drawBehind { }`) instead of value-based ones (`Modifier.offset(x, y)`, `Modifier.background(animatedColor)`) for scroll/animation state.
