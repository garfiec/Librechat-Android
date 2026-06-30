package com.garfiec.librechat.feature.chat.viewmodel.delegate

import co.touchlab.kermit.Logger
import com.garfiec.librechat.core.common.network.ConnectivityObserver
import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.data.repository.ChatRepository
import com.garfiec.librechat.core.model.Attachment
import com.garfiec.librechat.core.model.StreamEvent
import com.garfiec.librechat.core.model.error.UserKeyError
import com.garfiec.librechat.core.model.error.parseUserKeyError
import com.garfiec.librechat.feature.chat.components.artifact.ArtifactType
import com.garfiec.librechat.feature.chat.viewmodel.ActiveToolCall
import com.garfiec.librechat.feature.chat.viewmodel.ChatScreenState
import com.garfiec.librechat.feature.chat.viewmodel.ChatStateHandle
import com.garfiec.librechat.feature.chat.viewmodel.RetryInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Owns the streaming session lifecycle: the SSE collection job, the text buffer and its
 * throttled flush to UI state, the per-event dispatch ([handleStreamEvent]), stream
 * resume on app foreground, and network-error auto-reconnect.
 *
 * Collaborators are injected: comparison routing, subagent traces, office-doc previews,
 * and send completion (the `created`/`final` milestones) are owned by their own delegates;
 * this one is the hub that drives them as events arrive. The send paths in `ChatViewModel`
 * build a request flow and hand it to [launchStream]; everything downstream lives here.
 */
class StreamingManagerDelegate(
    private val stateHandle: ChatStateHandle,
    private val chatRepository: ChatRepository,
    private val connectivityObserver: ConnectivityObserver,
    private val comparisonDelegate: ComparisonModeDelegate,
    private val subagentTraceDelegate: SubagentTraceDelegate,
    private val officePreviewDelegate: OfficePreviewDelegate,
    private val completionDelegate: SendCompletionDelegate,
    private val queueDelegate: MessageQueueDelegate,
    /** Emits a typed user-provided-key error for one-shot UI surfacing (snackbar + CTA). */
    private val emitUserKeyError: (UserKeyError) -> Unit,
    /** Reloads the conversation from the server (VM-owned Room observer). */
    private val reloadConversation: (String) -> Unit,
    private val isNewConversation: () -> Boolean,
    private val isHandedOffNewChat: () -> Boolean,
) {

    private val scope get() = stateHandle.scope

    private var streamJob: Job? = null
    private var streamingUpdateJob: Job? = null
    private val streamingBuffer = StringBuilder()
    private var streamingBufferDirty = false
    private var wasStreaming = false

    /** Tracks whether the last stream failure was a network error, to enable auto-reconnect. */
    private var lastErrorWasNetwork = false

    /** Job for the connectivity observer; started lazily only when a network error occurs. */
    private var connectivityJob: Job? = null

    /** True when the current stream is from an edit, regenerate, or continue operation. */
    var isEditOrRegenerate = false
        private set

    /**
     * Resets the streaming-internal session state (buffer, edit flag, traces) and starts the
     * throttled updater. Does NOT touch [stateHandle] — callers that already mutate UI state
     * for their send (e.g. the optimistic-insert path) use this; [prepareForStreaming] wraps
     * it with the standard streaming-field reset.
     */
    fun beginStreaming(isEdit: Boolean) {
        isEditOrRegenerate = isEdit
        streamingBuffer.clear()
        streamingBufferDirty = false
        subagentTraceDelegate.reset()
        officePreviewDelegate.reset()
        startStreamingUpdater()
    }

    /**
     * Resets streaming-related UI state and the buffer in preparation for a new stream
     * (edit, regenerate, or continue).
     */
    fun prepareForStreaming(isEdit: Boolean) {
        stateHandle.update {
            copy(
                isStreaming = true,
                streamingContent = "",
                activeToolCalls = emptyList(),
                streamingAttachments = emptyList(),
                error = null,
            )
        }
        beginStreaming(isEdit)
    }

    /**
     * Cancels any in-flight stream and launches collection of [flow]. [onTerminated] runs
     * after collection completes (success, error, or normal end) — the send paths use it as
     * a safety net for flows that end without a Final/Error event.
     */
    fun launchStream(flow: Flow<StreamEvent>, onTerminated: suspend () -> Unit = {}) {
        streamJob?.cancel()
        streamJob = scope.launch {
            collectStreamSafely(flow)
            onTerminated()
        }
    }

    /** Cancels the active stream, stops the updater, and clears the buffer (nav reset / handoff). */
    fun reset() {
        streamJob?.cancel()
        streamJob = null
        stopStreamingUpdater()
        streamingBuffer.clear()
        streamingBufferDirty = false
    }

    private suspend fun collectStreamSafely(stream: Flow<StreamEvent>) {
        try {
            stream.collect { event -> handleStreamEvent(event) }
        } catch (e: CancellationException) {
            throw e // Never swallow cancellation
        } catch (e: Exception) {
            Logger.e(e) { "Stream collection failed" }
            stopStreamingUpdater()
            // Preserve partial content so users can read/copy what was received
            val partialContent = streamingBuffer.toString()
            stateHandle.update {
                copy(
                    isStreaming = false,
                    streamingContent = partialContent,
                    activeToolCalls = emptyList(),
                    streamingAttachments = emptyList(),
                    error = e.message ?: "Chat request failed",
                )
            }
            comparisonDelegate.endStreaming()
            // Don't auto-drain into a failed turn — hold the queue for the user (mirrors the
            // StreamEvent.Error branch; a flow-level exception ends the stream the same way).
            queueDelegate.pause()
            // If the server already created a conversation, fetch whatever it persisted
            val conversationId = stateHandle.state.conversationId
            if (conversationId != null) {
                reloadConversation(conversationId)
            }
        }
    }

    private fun handleStreamEvent(event: StreamEvent) {
        // In comparison mode the delegate fans streaming deltas/tool-calls into the dual
        // panes; if it consumed the event, skip the single-stream handling below.
        if (comparisonDelegate.routeEvent(event)) return
        when (event) {
            is StreamEvent.Created -> handleCreated(event)
            is StreamEvent.ContentDelta -> {
                streamingBuffer.append(event.chunk)
                streamingBufferDirty = true
            }
            is StreamEvent.ThinkingDelta -> {
                streamingBuffer.append(event.chunk)
                streamingBufferDirty = true
            }
            is StreamEvent.Final -> handleFinal(event)
            is StreamEvent.Error -> {
                stopStreamingUpdater()
                // Track network errors so auto-reconnect can kick in when connectivity returns
                lastErrorWasNetwork = event.isNetworkError
                if (event.isNetworkError) {
                    startConnectivityObserver()
                }
                // Try to parse the message as a typed user-provided-key error envelope.
                // If recognized, emit a one-shot effect so the UI can surface a snackbar
                // with a deep-link CTA to Settings → Provider Keys, and skip the generic
                // `error = event.message` fallback to avoid double-surfacing.
                val keyError = parseUserKeyError(event.message)
                // Preserve partial content so users can read/copy what was received
                val partialContent = streamingBuffer.toString()
                stateHandle.update {
                    copy(
                        isStreaming = false,
                        streamingContent = partialContent,
                        error = if (keyError != null) null else event.message,
                        retryInfo = null,
                        activeToolCalls = emptyList(),
                        streamingAttachments = emptyList(),
                    )
                }
                comparisonDelegate.endStreaming()
                // Don't auto-drain into a failed turn — hold the queue for the user.
                queueDelegate.pause()
                if (keyError != null) {
                    emitUserKeyError(keyError)
                }
                // If the server already created a conversation, fetch whatever it persisted
                val conversationId = stateHandle.state.conversationId
                if (conversationId != null) {
                    reloadConversation(conversationId)
                }
            }
            is StreamEvent.Retrying -> {
                stateHandle.update {
                    copy(
                        retryInfo = RetryInfo(
                            attempt = event.attempt,
                            maxAttempts = event.maxAttempts,
                        ),
                    )
                }
            }
            is StreamEvent.ToolCallStart -> {
                val newToolCall = ActiveToolCall(
                    id = event.toolCallId,
                    name = event.toolName,
                    input = event.input,
                )
                stateHandle.update {
                    copy(activeToolCalls = activeToolCalls + newToolCall)
                }
            }
            is StreamEvent.ToolCallComplete -> {
                stateHandle.update {
                    val updated = activeToolCalls.map { tc ->
                        if (tc.id == event.toolCallId) {
                            tc.copy(isComplete = true, output = event.output)
                        } else {
                            tc
                        }
                    }
                    copy(activeToolCalls = updated)
                }
                // If this was a `subagent` tool_call, freeze its live trace —
                // the child run is done; stop accumulating for that key.
                subagentTraceDelegate.onParentToolCallResolved(event.toolCallId)
            }
            is StreamEvent.AttachmentCreated -> {
                val attachment = Attachment(
                    fileId = event.fileId,
                    filename = event.filename,
                    filepath = event.filepath,
                    type = event.type,
                    toolCallId = event.toolCallId,
                    width = event.width,
                    height = event.height,
                    status = event.status,
                    text = event.text,
                    textFormat = event.textFormat,
                    previewError = event.previewError,
                )
                // Office-doc previews (v0.8.6) arrive twice per file_id (pending →
                // ready/failed) — route through the delegate for upsert-by-file_id +
                // poll-while-pending. Ordinary attachments keep the simple append path.
                if (ArtifactType.isOfficePreviewMime(event.type)) {
                    officePreviewDelegate.onAttachment(attachment)
                } else {
                    stateHandle.update {
                        copy(streamingAttachments = streamingAttachments + attachment)
                    }
                }
            }
            is StreamEvent.Sync -> {
                // Resume snapshot: `aggregatedContent` is the authoritative state of
                // the response so far, so we REPLACE (not append) the streaming
                // pipeline's fields from it — both the text buffer and the tool-call
                // list. Any pendingEvents in the same frame arrive as their own
                // StreamEvents after this and fold on top via the normal handlers.
                if (lastErrorWasNetwork) {
                    lastErrorWasNetwork = false
                    cancelConnectivityObserver()
                }
                stateHandle.update {
                    if (retryInfo != null) copy(retryInfo = null) else this
                }
                val textContent = event.aggregatedContent
                    .mapNotNull { it.text }
                    .joinToString("")
                streamingBuffer.clear()
                streamingBuffer.append(textContent)
                streamingBufferDirty = true

                // Rebuild active tool calls from the snapshot's tool_call parts so an
                // in-progress image gen (or any tool call) started before we resumed
                // still renders its live card. The same ActiveToolCall the live path
                // produces, so the existing StreamingToolCallCard / ImageGenCard render
                // it identically. A part with a non-blank output is already complete.
                val syncedToolCalls = event.aggregatedContent
                    .mapNotNull { part -> part.toolCall?.takeIf { !it.id.isNullOrBlank() } }
                    .map { tc ->
                        ActiveToolCall(
                            id = tc.id.orEmpty(),
                            name = tc.name.orEmpty(),
                            input = tc.args?.toString(),
                            isComplete = !tc.output.isNullOrBlank(),
                            output = tc.output,
                        )
                    }
                stateHandle.update { copy(activeToolCalls = syncedToolCalls) }
                flushStreamingBuffer()
            }
            is StreamEvent.Step -> { /* no-op */ }
            is StreamEvent.ContextSummary -> {
                // Server compacted earlier turns into a summary. The compacted text is
                // persisted to the final message as a SUMMARY content part and rendered
                // there; nothing extra to do during streaming.
            }
            is StreamEvent.SubagentUpdate -> subagentTraceDelegate.onUpdate(event)
            is StreamEvent.TitleUpdate -> handleTitleUpdate(event)
            is StreamEvent.ContextUsageUpdate -> {
                // Latest context-window snapshot drives the gauge. In-memory only.
                stateHandle.update { copy(contextUsage = event.usage) }
            }
            is StreamEvent.TokenUsageUpdate -> {
                // Per-call provider usage; the gauge denominator comes from the context
                // snapshot, but the breakdown sheet shows Input/Output from this. In-memory only.
                stateHandle.update { copy(tokenUsage = event.usage) }
            }
        }
    }

    /**
     * Eager mid-stream title reveal (v0.8.7 `titleTiming: immediate`). Updates the
     * in-memory title only — writing to Room mid-stream would re-emit the
     * loadConversation observer and clobber the in-place streaming view (see the
     * streaming-anchor invariant). The post-stream title refetch persists it.
     */
    private fun handleTitleUpdate(event: StreamEvent.TitleUpdate) {
        val current = stateHandle.state.conversationId
        if (current != null && current != event.conversationId) return
        stateHandle.update { copy(conversationTitle = event.title) }
    }

    private fun handleCreated(event: StreamEvent.Created) {
        if (lastErrorWasNetwork) {
            lastErrorWasNetwork = false
            cancelConnectivityObserver()
        }
        stateHandle.update {
            if (retryInfo != null) {
                copy(conversationId = event.conversationId, retryInfo = null)
            } else {
                copy(conversationId = event.conversationId)
            }
        }
        completionDelegate.onConversationCreated(event.conversationId, isNewConversation())
    }

    private fun handleFinal(event: StreamEvent.Final) {
        stopStreamingUpdater()
        // The stream has ended: any office-doc attachment still `pending` (its
        // `ready` SSE update may never arrive once the run closes) now falls back
        // to polling GET /api/files/:id/preview. De-duped + bounded in the delegate.
        officePreviewDelegate.onStreamEnded()
        val isComparison = stateHandle.state.comparisonState.isEnabled
        val conversationId = stateHandle.state.conversationId
            ?: event.conversation?.conversationId
        val completedResponseText = if (isComparison) {
            comparisonDelegate.primaryContent()
        } else {
            streamingBuffer.toString()
        }
        val shouldAutoRead = !isEditOrRegenerate
        // Make sure the resolved conversation id is in state for the completion handlers.
        if (conversationId != null) {
            stateHandle.update { copy(conversationId = conversationId) }
        }
        if (isComparison) {
            // Comparison reconciles via background reload (no in-memory finalize to fold the
            // streaming-clear into), so clear the single-stream UI fields now.
            stateHandle.update {
                copy(
                    isStreaming = false,
                    streamingContent = "",
                    activeToolCalls = emptyList(),
                    streamingAttachments = emptyList(),
                )
            }
            comparisonDelegate.onFinal((event.responseMessage ?: event.message)?.messageId)
        }
        // Non-comparison chats fold the streaming-clear into finalizeChatDisplay (atomic
        // bubble→message swap; see SendCompletionDelegate.onFinal).
        completionDelegate.onFinal(
            event = event,
            conversationId = conversationId,
            completedResponseText = completedResponseText,
            shouldAutoRead = shouldAutoRead,
            isNewConversation = isNewConversation(),
            isHandedOffNewChat = isHandedOffNewChat(),
            isComparison = isComparison,
        )
        // Fallback for degenerate finals: a non-comparison stream that ends with no
        // conversation id (and thus nothing to finalize) never reaches finalizeChatDisplay,
        // so its streaming fields would otherwise stay set. No-op once the in-memory
        // finalize (normal/temp) or the comparison branch above has already cleared them.
        if (stateHandle.state.isStreaming) {
            stateHandle.update {
                copy(
                    isStreaming = false,
                    streamingContent = "",
                    activeToolCalls = emptyList(),
                    streamingAttachments = emptyList(),
                )
            }
        }
        // Reply finished cleanly: fire the next queued follow-up (if any, and not paused).
        // isStreaming is already false here, so the next send respects the no-Room-write-
        // while-streaming invariant.
        queueDelegate.drainNext()
    }

    /**
     * Launches a periodic coroutine that flushes the [streamingBuffer] to UI state
     * at most every [STREAMING_UI_UPDATE_INTERVAL_MS] ms. This avoids recomposition spam
     * from high-frequency SSE chunks (each chunk would otherwise trigger a full state copy).
     */
    private fun startStreamingUpdater() {
        streamingUpdateJob?.cancel()
        streamingUpdateJob = scope.launch {
            while (isActive) {
                delay(STREAMING_UI_UPDATE_INTERVAL_MS)
                flushStreamingBuffer()
            }
        }
    }

    /**
     * Flushes the streaming buffer to UI state if it has been modified since the last flush.
     * Called both periodically (by the updater) and immediately on stream completion/error.
     */
    private fun flushStreamingBuffer() {
        if (!streamingBufferDirty) return
        streamingBufferDirty = false
        stateHandle.update { copy(streamingContent = streamingBuffer.toString()) }
    }

    /**
     * Stops the periodic streaming updater and performs a final flush so the last
     * chunk is never lost.
     */
    private fun stopStreamingUpdater() {
        streamingUpdateJob?.cancel()
        streamingUpdateJob = null
        flushStreamingBuffer()
    }

    fun stopGeneration() {
        val conversationId = stateHandle.state.conversationId ?: return
        // A manual stop usually means "wait" — hold the queue instead of firing the next item.
        queueDelegate.pause()
        streamJob?.cancel()
        stopStreamingUpdater()
        streamingBuffer.clear()
        streamingBufferDirty = false
        scope.launch {
            val abortResult = chatRepository.abortChat(conversationId)
            if (abortResult is Result.Error) {
                Logger.w(abortResult.exception) { "Failed to abort chat: ${abortResult.message}" }
            }
            stateHandle.update {
                copy(
                    isStreaming = false,
                    streamingContent = "",
                    activeToolCalls = emptyList(),
                    streamingAttachments = emptyList(),
                )
            }
            comparisonDelegate.endStreaming(clearContent = true)
            // Refresh messages from server so the message tree reflects
            // the partially-streamed response that was aborted.
            reloadConversation(conversationId)
        }
    }

    fun onPause() {
        wasStreaming = stateHandle.state.isStreaming
        if (wasStreaming) {
            streamJob?.cancel()
            stopStreamingUpdater()
        }
    }

    fun onResume() {
        if (!wasStreaming) return
        wasStreaming = false

        val conversationId = stateHandle.state.conversationId ?: return

        scope.launch {
            try {
                val status = chatRepository.checkStreamStatus(conversationId)
                if (status.active) {
                    stateHandle.update { copy(isStreaming = true) }
                    resumeStream(conversationId)
                } else {
                    stateHandle.update { copy(isStreaming = false, streamingContent = "") }
                    reloadConversation(conversationId)
                }
            } catch (e: Exception) {
                Logger.e(e) { "Could not resume stream" }
                stateHandle.update {
                    copy(
                        isStreaming = false,
                        streamingContent = "",
                        error = "Could not resume stream",
                    )
                }
            }
        }
    }

    /**
     * Shared resume logic: clears the buffer, starts the updater, and launches
     * stream collection. Caller is responsible for setting any UI state fields
     * (e.g. isStreaming, error) before calling this.
     */
    private fun resumeStream(conversationId: String) {
        streamingBuffer.clear()
        streamingBufferDirty = false
        startStreamingUpdater()
        streamJob?.cancel()
        streamJob = scope.launch {
            collectStreamSafely(chatRepository.resumeStream(conversationId))
        }
    }

    fun resumeActiveStreamIfNeeded(conversationId: String) {
        scope.launch {
            try {
                val status = chatRepository.checkStreamStatus(conversationId)
                if (status.active) {
                    stateHandle.update {
                        copy(
                            isStreaming = true,
                            screenState = ChatScreenState.ACTIVE,
                        )
                    }
                    resumeStream(conversationId)
                }
            } catch (e: Exception) {
                Logger.d(e) { "No active stream to resume for $conversationId" }
            }
        }
    }

    /**
     * Starts observing connectivity for auto-reconnect after a network error.
     * Cancels any existing observer first. The observer self-cancels after recovery fires.
     */
    private fun startConnectivityObserver() {
        connectivityJob?.cancel()
        connectivityJob = scope.launch {
            var wasConnected = true
            connectivityObserver.isConnected.collect { connected ->
                val recovered = !wasConnected && connected
                wasConnected = connected
                if (recovered) {
                    attemptNetworkRecovery()
                }
            }
        }
    }

    /** Cancels the connectivity observer and clears the network-error flag. */
    private fun cancelConnectivityObserver() {
        connectivityJob?.cancel()
        connectivityJob = null
    }

    /**
     * Called when network connectivity transitions from offline to online.
     * If the last stream ended due to a network error, attempts to resume it
     * or falls back to reloading the conversation from the server.
     */
    private fun attemptNetworkRecovery() {
        if (!lastErrorWasNetwork) return
        val state = stateHandle.state
        val conversationId = state.conversationId ?: return
        if (state.isStreaming) return

        lastErrorWasNetwork = false
        cancelConnectivityObserver()
        Logger.d { "Network recovered, attempting to resume conversation $conversationId" }

        scope.launch {
            try {
                val status = chatRepository.checkStreamStatus(conversationId)
                if (status.active) {
                    stateHandle.update {
                        copy(
                            isStreaming = true,
                            error = null,
                            retryInfo = null,
                        )
                    }
                    resumeStream(conversationId)
                } else {
                    // Stream expired while offline — reload conversation from server
                    stateHandle.update { copy(error = null, retryInfo = null) }
                    reloadConversation(conversationId)
                }
            } catch (e: Exception) {
                Logger.w(e) { "Network recovery: could not check stream status" }
            }
        }
    }

    private companion object {
        /** Minimum interval between streaming UI state updates to avoid recomposition spam. */
        const val STREAMING_UI_UPDATE_INTERVAL_MS = 50L
    }
}
