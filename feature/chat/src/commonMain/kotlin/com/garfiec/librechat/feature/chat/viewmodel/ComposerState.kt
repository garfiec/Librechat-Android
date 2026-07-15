package com.garfiec.librechat.feature.chat.viewmodel

import androidx.compose.runtime.Immutable
import com.garfiec.librechat.core.ui.components.ModelParameters
import com.garfiec.librechat.feature.chat.components.AttachedFile

/**
 * The editable composer surface: the draft text, any send-block reason, and the active
 * queued-edit session. Written by [ChatViewModel], PresetPromptDelegate, and the platform
 * voice-input delegates.
 */
@Immutable
data class ComposerState(
    val inputText: String = "",
    /** Set when a send was blocked for a selection/readiness reason. Resolved to a
     *  user-facing string in the Compose layer. Null means no send-block to show. */
    val sendBlockReason: SendBlockReason? = null,
    /** Non-null while a queued item is being edited in the composer (queued-edit mode). Holds the
     *  stashed new-message draft + the item's slot so both are restored on commit/cancel. */
    val editingQueuedItem: QueuedEditSession? = null,
    /** True while a tapped send is parked waiting for its attachment(s) to finish uploading (see
     *  `ChatViewModel.withUploadGate`). Drives the composer's send button into a spinner the user
     *  can tap to cancel the deferred send. */
    val isAwaitingUploadSend: Boolean = false,
)

/**
 * Reasons why a send attempt was blocked. Resolved to a user-facing string in the Compose
 * layer via `stringResource`, so the ViewModel stays free of hard-coded English UI copy.
 */
sealed interface SendBlockReason {
    data object SelectAgent : SendBlockReason
    data object SelectModel : SendBlockReason
    data object AgentsUnavailable : SendBlockReason
    data object AgentNotAvailable : SendBlockReason
    data object ModelNotAvailable : SendBlockReason
    data object ModelLoadFailed : SendBlockReason
}

/**
 * Snapshot of the editable composer surface (the "new message" draft) stashed when entering
 * queued-edit mode and restored on commit/cancel, so editing a queued item never clobbers what
 * the user was already composing.
 *
 * These fields mirror [QueuedMessage]'s source-config fields (model/endpoint/tools/mcp/params) and
 * are captured/applied by `ChatViewModel.captureComposer`/`applyComposer`/`toComposerSnapshot` — a
 * new composer setting must be threaded through all of them (no compiler enforcement).
 */
@Immutable
data class ComposerSnapshot(
    val text: String,
    val attachments: List<AttachedFile> = emptyList(),
    val endpoint: String,
    val model: String?,
    val enabledTools: Set<String> = emptySet(),
    val mcpServerNames: Set<String> = emptySet(),
    val modelParameters: ModelParameters = ModelParameters.DEFAULT,
)

/**
 * Active queued-edit session: the composer is loaded with [original]'s content + config for
 * editing, while [stashed] holds the new-message draft to restore afterward and [originalIndex]
 * is the FIFO slot to put the (possibly edited) item back into on commit/cancel.
 */
@Immutable
data class QueuedEditSession(
    val original: QueuedMessage,
    val originalIndex: Int,
    val stashed: ComposerSnapshot,
)
