package com.garfiec.librechat.feature.chat.viewmodel

import com.garfiec.librechat.core.model.endpoint.KeyState
import com.garfiec.librechat.core.model.usage.ContextUsage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * Per-delegate narrowed write handles over the shared [ChatStateHandle].
 *
 * Each delegate receives a handle that can mutate only the [ChatUiState] slices it owns
 * (enforced at compile time by the corresponding `*Writes` class exposing only those slices
 * as vars), plus the shared `error` channel. Reads stay global via [DelegateHandle.state]; a
 * delegate may legitimately read any slice. Writes go through each handle's `update`, whose block
 * receives a mutable writer and is applied as a single [ChatStateHandle.update] — so a multi-field
 * change is one StateFlow emission, preserving the atomic-transaction invariants (completion
 * flash, etc.).
 *
 * The root [ChatStateHandle] is held only by [ChatViewModel] for its orchestration
 * transactions; delegates never receive it. See feature/chat/CLAUDE.md.
 *
 * `error` is intentionally writable from every handle — it is a shared, transient banner
 * channel, not a per-delegate slice. Set it inside an `update` block alongside a slice write,
 * or via [DelegateHandle.setError] on its own.
 */

/**
 * Shared read/error surface for every delegate handle. Holds the global read view ([state]),
 * the delegate's [scope], and the shared-channel [setError]. Subclasses add only their typed
 * `update` (and, where needed, extra read views like [ModelSelectionHandle.stateFlow]) — the
 * per-delegate write narrowing lives entirely in each `*Writes` class.
 */
abstract class DelegateHandle(protected val root: ChatStateHandle) {
    val state: ChatUiState get() = root.state
    val scope: CoroutineScope get() = root.scope
    fun setError(message: String?) = root.update { copy(error = message) }
}

// ── StreamingManagerDelegate ──────────────────────────────────────────────
class StreamingWrites internal constructor(state: ChatUiState) {
    var content: MessagesState = state.content
    var conversation: ConversationMetaState = state.conversation
    var error: String? = state.error
    internal fun applyTo(s: ChatUiState) = s.copy(content = content, conversation = conversation, error = error)
}

class StreamingHandle(root: ChatStateHandle) : DelegateHandle(root) {
    fun update(block: StreamingWrites.() -> Unit) =
        root.update { StreamingWrites(this).apply(block).applyTo(this) }
}

// ── MessageTreeDelegate ───────────────────────────────────────────────────
class MessageTreeWrites internal constructor(state: ChatUiState) {
    var content: MessagesState = state.content
    var conversation: ConversationMetaState = state.conversation
    var error: String? = state.error
    internal fun applyTo(s: ChatUiState) = s.copy(content = content, conversation = conversation, error = error)
}

class MessageTreeHandle(root: ChatStateHandle) : DelegateHandle(root) {
    fun update(block: MessageTreeWrites.() -> Unit) =
        root.update { MessageTreeWrites(this).apply(block).applyTo(this) }
}

// ── MessageEditingDelegate ────────────────────────────────────────────────
class MessageEditingWrites internal constructor(state: ChatUiState) {
    var editing: MessageEditingState = state.editing
    var content: MessagesState = state.content
    var error: String? = state.error
    internal fun applyTo(s: ChatUiState) = s.copy(editing = editing, content = content, error = error)
}

class MessageEditingHandle(root: ChatStateHandle) : DelegateHandle(root) {
    fun update(block: MessageEditingWrites.() -> Unit) =
        root.update { MessageEditingWrites(this).apply(block).applyTo(this) }
}

// ── ComparisonModeDelegate ────────────────────────────────────────────────
class ComparisonWrites internal constructor(state: ChatUiState) {
    var comparisonState: ComparisonState = state.comparisonState
    var content: MessagesState = state.content
    var conversation: ConversationMetaState = state.conversation
    var error: String? = state.error
    internal fun applyTo(s: ChatUiState) = s.copy(
        comparisonState = comparisonState,
        content = content,
        conversation = conversation,
        error = error,
    )
}

class ComparisonHandle(root: ChatStateHandle) : DelegateHandle(root) {
    fun update(block: ComparisonWrites.() -> Unit) =
        root.update { ComparisonWrites(this).apply(block).applyTo(this) }
}

// ── ModelSelectionDelegate ────────────────────────────────────────────────
class ModelSelectionWrites internal constructor(state: ChatUiState) {
    var selection: ModelSelectionState = state.selection
    var error: String? = state.error
    internal fun applyTo(s: ChatUiState) = s.copy(selection = selection, error = error)
}

class ModelSelectionHandle(root: ChatStateHandle) : DelegateHandle(root) {
    /** Read-only observation of the full state (the seeder combines over agents/conversationId). */
    val stateFlow: StateFlow<ChatUiState> get() = root.stateFlow
    fun update(block: ModelSelectionWrites.() -> Unit) =
        root.update { ModelSelectionWrites(this).apply(block).applyTo(this) }
}

// ── EndpointKeyStatusDelegate ─────────────────────────────────────────────
// Narrower than ModelSelectionHandle on purpose: this delegate owns only the
// per-endpoint key-state map, so it may not touch the rest of the selection slice.
class EndpointKeyWrites internal constructor(state: ChatUiState) {
    var endpointKeyStates: Map<String, KeyState> = state.selection.endpointKeyStates
    var error: String? = state.error
    internal fun applyTo(s: ChatUiState) =
        s.copy(selection = s.selection.copy(endpointKeyStates = endpointKeyStates), error = error)
}

class EndpointKeyHandle(root: ChatStateHandle) : DelegateHandle(root) {
    fun update(block: EndpointKeyWrites.() -> Unit) =
        root.update { EndpointKeyWrites(this).apply(block).applyTo(this) }
}

// ── PresetPromptDelegate ──────────────────────────────────────────────────
class PresetPromptWrites internal constructor(state: ChatUiState) {
    var presetPrompts: PresetPromptState = state.presetPrompts
    var selection: ModelSelectionState = state.selection
    var composer: ComposerState = state.composer
    var error: String? = state.error
    internal fun applyTo(s: ChatUiState) = s.copy(
        presetPrompts = presetPrompts,
        selection = selection,
        composer = composer,
        error = error,
    )
}

class PresetPromptHandle(root: ChatStateHandle) : DelegateHandle(root) {
    fun update(block: PresetPromptWrites.() -> Unit) =
        root.update { PresetPromptWrites(this).apply(block).applyTo(this) }
}

// ── ConversationActionsDelegate ───────────────────────────────────────────
class ConversationActionsWrites internal constructor(state: ChatUiState) {
    var actions: ConversationActionsState = state.actions
    var conversation: ConversationMetaState = state.conversation
    var error: String? = state.error
    internal fun applyTo(s: ChatUiState) = s.copy(actions = actions, conversation = conversation, error = error)
}

class ConversationActionsHandle(root: ChatStateHandle) : DelegateHandle(root) {
    fun update(block: ConversationActionsWrites.() -> Unit) =
        root.update { ConversationActionsWrites(this).apply(block).applyTo(this) }
}

// ── SendCompletionDelegate ────────────────────────────────────────────────
class SendCompletionWrites internal constructor(state: ChatUiState) {
    var conversation: ConversationMetaState = state.conversation
    var error: String? = state.error
    internal fun applyTo(s: ChatUiState) = s.copy(conversation = conversation, error = error)
}

class SendCompletionHandle(root: ChatStateHandle) : DelegateHandle(root) {
    fun update(block: SendCompletionWrites.() -> Unit) =
        root.update { SendCompletionWrites(this).apply(block).applyTo(this) }
}

// ── MessageQueueDelegate ──────────────────────────────────────────────────
class QueueWrites internal constructor(state: ChatUiState) {
    var queue: QueueState = state.queue
    var error: String? = state.error
    internal fun applyTo(s: ChatUiState) = s.copy(queue = queue, error = error)
}

class QueueHandle(root: ChatStateHandle) : DelegateHandle(root) {
    fun update(block: QueueWrites.() -> Unit) =
        root.update { QueueWrites(this).apply(block).applyTo(this) }
}

// ── InConversationSearchDelegate ──────────────────────────────────────────
class SearchWrites internal constructor(state: ChatUiState) {
    var search: ChatSearchState = state.search
    var error: String? = state.error
    internal fun applyTo(s: ChatUiState) = s.copy(search = search, error = error)
}

class SearchHandle(root: ChatStateHandle) : DelegateHandle(root) {
    fun update(block: SearchWrites.() -> Unit) =
        root.update { SearchWrites(this).apply(block).applyTo(this) }
}

// ── FavoritesDelegate ─────────────────────────────────────────────────────
class FavoritesWrites internal constructor(state: ChatUiState) {
    var favorites: FavoritesState = state.favorites
    var error: String? = state.error
    internal fun applyTo(s: ChatUiState) = s.copy(favorites = favorites, error = error)
}

class FavoritesHandle(root: ChatStateHandle) : DelegateHandle(root) {
    fun update(block: FavoritesWrites.() -> Unit) =
        root.update { FavoritesWrites(this).apply(block).applyTo(this) }
}

// ── SubagentTraceDelegate ─────────────────────────────────────────────────
class SubagentWrites internal constructor(state: ChatUiState) {
    var subagents: SubagentState = state.subagents
    var error: String? = state.error
    internal fun applyTo(s: ChatUiState) = s.copy(subagents = subagents, error = error)
}

class SubagentHandle(root: ChatStateHandle) : DelegateHandle(root) {
    fun update(block: SubagentWrites.() -> Unit) =
        root.update { SubagentWrites(this).apply(block).applyTo(this) }
}

// ── OfficePreviewDelegate ─────────────────────────────────────────────────
class OfficePreviewWrites internal constructor(state: ChatUiState) {
    var content: MessagesState = state.content
    var error: String? = state.error
    internal fun applyTo(s: ChatUiState) = s.copy(content = content, error = error)
}

class OfficePreviewHandle(root: ChatStateHandle) : DelegateHandle(root) {
    fun update(block: OfficePreviewWrites.() -> Unit) =
        root.update { OfficePreviewWrites(this).apply(block).applyTo(this) }
}

// ── ContextProjectionDelegate ─────────────────────────────────────────────
// Narrow on purpose: this delegate only seeds/clears the context-usage gauge, so it may write
// nothing on the content slice but `contextUsage`.
class ContextProjectionWrites internal constructor(state: ChatUiState) {
    var contextUsage: ContextUsage? = state.content.contextUsage
    var error: String? = state.error
    internal fun applyTo(s: ChatUiState) =
        s.copy(content = s.content.copy(contextUsage = contextUsage), error = error)
}

class ContextProjectionHandle(root: ChatStateHandle) : DelegateHandle(root) {
    /** Read-only observation of the full state (the observer keys off conversation/endpoint/model). */
    val stateFlow: StateFlow<ChatUiState> get() = root.stateFlow
    fun update(block: ContextProjectionWrites.() -> Unit) =
        root.update { ContextProjectionWrites(this).apply(block).applyTo(this) }
}

// ── Platform voice input (VoiceInputDelegate / IosVoiceInput) ─────────────
class VoiceWrites internal constructor(state: ChatUiState) {
    var voice: VoiceState = state.voice
    var composer: ComposerState = state.composer
    var error: String? = state.error
    internal fun applyTo(s: ChatUiState) = s.copy(voice = voice, composer = composer, error = error)
}

class VoiceHandle(root: ChatStateHandle) : DelegateHandle(root) {
    fun update(block: VoiceWrites.() -> Unit) =
        root.update { VoiceWrites(this).apply(block).applyTo(this) }
}

// ── Platform TTS (TextToSpeechDelegate / IosTts) ──────────────────────────
class TtsWrites internal constructor(state: ChatUiState) {
    var voice: VoiceState = state.voice
    var error: String? = state.error
    internal fun applyTo(s: ChatUiState) = s.copy(voice = voice, error = error)
}

class TtsHandle(root: ChatStateHandle) : DelegateHandle(root) {
    fun update(block: TtsWrites.() -> Unit) =
        root.update { TtsWrites(this).apply(block).applyTo(this) }
}

// ── Error-only (FileAttachmentDelegate / IosFileHandler) ──────────────────
/** For delegates that keep their real data in a separate flow and only surface errors here. */
class ErrorOnlyHandle(root: ChatStateHandle) : DelegateHandle(root)
