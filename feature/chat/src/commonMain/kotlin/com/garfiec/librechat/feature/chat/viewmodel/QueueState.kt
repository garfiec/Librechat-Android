package com.garfiec.librechat.feature.chat.viewmodel

import androidx.compose.runtime.Immutable
import com.garfiec.librechat.core.data.endpoint.EndpointDispatch
import com.garfiec.librechat.core.model.request.EphemeralAgent
import com.garfiec.librechat.core.ui.components.ModelParameters
import com.garfiec.librechat.feature.chat.components.AttachedFile
import kotlinx.serialization.json.JsonObject

/**
 * The in-memory FIFO follow-up queue staged while a reply streams. Owned by
 * [com.garfiec.librechat.feature.chat.viewmodel.delegate.MessageQueueDelegate];
 * never persisted (lives and dies with the ViewModel).
 */
@Immutable
data class QueueState(
    /** Follow-up messages queued while a reply streams, drained FIFO on each successful
     *  completion. Rendered as ghost bubbles after the streaming bubble; never part of the
     *  message tree. In-memory only (dropped on conversation switch / process death). */
    val messageQueue: List<QueuedMessage> = emptyList(),
    /** True after Stop/stream-error with a non-empty queue: draining is held until the user
     *  explicitly taps "Send queued". A successful Final drains automatically instead. */
    val isQueuePaused: Boolean = false,
)

/**
 * A follow-up message the user queued while a response was streaming, waiting to be
 * auto-sent (FIFO) once the current reply completes. Rendered as a dimmed "ghost" bubble
 * after the streaming bubble — it is NOT part of the message tree.
 *
 * Captures a full snapshot of the send config **at queue time** (model/endpoint/tools/
 * webSearch/attachments + the resolved [dispatch]/[ephemeralAgent]), so a mid-stream model
 * switch never retro-edits an already-queued item. The live-lineage fields
 * (conversationId / parentMessageId / userMessageId) are deliberately NOT snapshotted — they
 * are recomputed from the current tree when the item actually fires.
 *
 * [attachments] holds the already-uploaded [AttachedFile]s (not bare FileReferences) so editing
 * a queued item restores its composer chips — including the local-uri image thumbnail — intact.
 */
@Immutable
data class QueuedMessage(
    /** Stable local id for list keying, edit, and reorder. Not a server message id. */
    val localId: String,
    val text: String,
    val attachments: List<AttachedFile> = emptyList(),
    val endpoint: String,
    val model: String?,
    val agentId: String?,
    val enabledTools: Set<String> = emptySet(),
    /** Selected ephemeral MCP servers — snapshotted so editing the item restores its tool state. */
    val mcpServerNames: Set<String> = emptySet(),
    /** Full composer parameters (web search, reasoning effort, etc.) — restored to the composer on edit. */
    val modelParameters: ModelParameters = ModelParameters.DEFAULT,
    /**
     * Non-default model params (provider-keyed) serialized for the wire, snapshotted at enqueue time
     * so a queued send carries the params it was composed with. Null when nothing was customized.
     */
    val modelParamsPayload: JsonObject? = null,
    val ephemeralAgent: EphemeralAgent? = null,
    val dispatch: EndpointDispatch,
    val isTemporary: Boolean = false,
    /**
     * The active account when this item was queued. A drain guard drops any item whose account no
     * longer matches the active one (the user switched accounts since queueing), so a follow-up
     * composed under account A can never be POSTed to account B's server under B's bearer. Null for
     * items composed before multi-account (or in tests) — treated as "matches any", never dropped.
     */
    val accountId: String? = null,
)

/** Loads a queued item's content + config onto the composer surface when entering edit mode. */
fun QueuedMessage.toComposerSnapshot(): ComposerSnapshot = ComposerSnapshot(
    text = text,
    attachments = attachments,
    endpoint = endpoint,
    model = model,
    enabledTools = enabledTools,
    mcpServerNames = mcpServerNames,
    modelParameters = modelParameters,
)
