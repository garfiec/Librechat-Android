package com.garfiec.librechat.feature.chat.viewmodel

import androidx.compose.runtime.Immutable
import com.garfiec.librechat.core.model.response.UploadRoute
import com.garfiec.librechat.core.ui.components.ModelParameters
import com.garfiec.librechat.feature.chat.components.AttachedFile
import com.garfiec.librechat.feature.chat.viewmodel.delegate.PickedFile

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
    /**
     * Files picked in Manual routing mode that are waiting on the user's choice. Non-null means
     * the routing sheet is open and NOTHING has been uploaded yet — cancelling leaves no orphaned
     * server record. Written only by [ChatViewModel].
     */
    val pendingUploadRouting: PendingUploadRouting? = null,
    /**
     * How many picks are between the picker handing files back and those files reaching either the
     * upload handler or [pendingUploadRouting].
     *
     * Intake is asynchronous — it reads the routing preference and may wait on the agent's provider
     * — and during that window the files are in no list any send gate inspects. Without this a send
     * fired in the gap goes out with no attachment and the files ride the *next* message.
     *
     * A **count**, not a flag: nothing disables the attach affordance while intake runs, and a
     * share can arrive on top of a pick, so two intakes overlap readily. With a boolean the first
     * to finish clears the gate while the second is still resolving, re-opening the exact window
     * this exists to close. Written only by [ChatViewModel].
     */
    val resolvingPickCount: Int = 0,
) {
    /** True while at least one intake is still in flight. See [resolvingPickCount]. */
    val isResolvingPickedFiles: Boolean get() = resolvingPickCount > 0
}

/**
 * One file in a staged routing batch: the pick itself, the route currently selected for it, and
 * whether the user may change that.
 *
 * [choosable] is false for files with only one usable mode (an image, or a type the server cannot
 * extract). Those still ride along so the batch stays whole and the sheet can show what will
 * happen to them — the sheet disables their control rather than hiding the file.
 */
@Immutable
data class PendingUploadFile(
    val file: PickedFile,
    val route: UploadRoute,
    val choosable: Boolean,
)

/**
 * A staged batch of picked files awaiting a routing decision.
 *
 * [context] is snapshotted with the batch: the sheet is a window in which the user can switch
 * endpoints, and a file must be routed against the selection it was picked under — the same reason
 * `FileAttachmentDelegate` snapshots the endpoint before sizing.
 */
@Immutable
data class PendingUploadRouting(
    val files: List<PendingUploadFile>,
    val context: UploadRoutingContext,
)

/** The selection a staged batch was described against. */
@Immutable
data class UploadRoutingContext(
    val endpoint: String,
    val endpointType: String?,
    val agentProvider: String?,
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
