package com.garfiec.librechat.feature.chat.viewmodel.delegate

import co.touchlab.kermit.Logger
import com.garfiec.librechat.core.common.ToolConstants
import com.garfiec.librechat.core.common.identity.AccountId
import com.garfiec.librechat.core.common.identity.ActiveAccountProvider
import com.garfiec.librechat.core.common.identity.currentAccountId
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
import com.garfiec.librechat.feature.chat.viewmodel.RetryInfo
import com.garfiec.librechat.feature.chat.util.normalizeAbortedFrame
import com.garfiec.librechat.feature.chat.viewmodel.StreamingHandle
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
    private val handle: StreamingHandle,
    private val chatRepository: ChatRepository,
    private val activeAccountProvider: ActiveAccountProvider,
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

    private val scope get() = handle.scope

    private var streamJob: Job? = null
    private var streamingUpdateJob: Job? = null
    private val streamingBuffer = StringBuilder()
    private var streamingBufferDirty = false
    private var wasStreaming = false

    /**
     * The account active when the current stream started (origin-capture provenance): its finalize —
     * message cache, conversation save, gen_title — lands minutes later, possibly after the user
     * switched accounts. Threaded to [SendCompletionDelegate] so those writes attribute to the account
     * that initiated the stream, not the live active one. Captured at every stream start ([beginStreaming],
     * [resumeStream]).
     */
    private var streamOriginAccountId: AccountId? = null

    /** Tracks whether the last stream failure was a network error, to enable auto-reconnect. */
    private var lastErrorWasNetwork = false

    /**
     * A Stop has been asked for and the aborted `final` frame has not arrived yet. The stream is
     * still live and collecting throughout this window (that is what preserves the partial), so
     * `isStreaming` cannot serve as the re-entry guard for a second Stop tap.
     *
     * Cleared wherever a stream session begins or ends — [beginStreaming], [resumeStream],
     * [reset], [handleFinal], [forceStopStream] — so a pending abort can never suppress Stop on a
     * later stream.
     */
    private var abortRequested = false

    /** Job for the connectivity observer; started lazily only when a network error occurs. */
    private var connectivityJob: Job? = null

    /** True when the current stream is from an edit, regenerate, or continue operation. */
    var isEditOrRegenerate = false
        private set

    /**
     * Resets the streaming-internal session state (buffer, edit flag, traces) and starts the
     * throttled updater. Does NOT touch UI state — callers that already mutate UI state
     * for their send (e.g. the optimistic-insert path) use this; [prepareForStreaming] wraps
     * it with the standard streaming-field reset.
     */
    fun beginStreaming(isEdit: Boolean) {
        isEditOrRegenerate = isEdit
        abortRequested = false
        // Capture the origin account at stream start so a post-switch finalize attributes to it.
        streamOriginAccountId = activeAccountProvider.currentAccountId()
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
        handle.update {
            content = content.copy(
                isStreaming = true,
                streamingContent = "",
                activeToolCalls = emptyList(),
                streamingAttachments = emptyList(),
            )
            error = null
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
        abortRequested = false
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
            handle.update {
                content = content.copy(
                    isStreaming = false,
                    streamingContent = partialContent,
                    activeToolCalls = emptyList(),
                    streamingAttachments = emptyList(),
                )
                error = e.message ?: "Chat request failed"
            }
            comparisonDelegate.endStreaming()
            // Don't auto-drain into a failed turn — hold the queue for the user (mirrors the
            // StreamEvent.Error branch; a flow-level exception ends the stream the same way).
            queueDelegate.pause()
            // If the server already created a conversation, fetch whatever it persisted
            val conversationId = handle.state.conversationId
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
                handle.update {
                    content = content.copy(
                        isStreaming = false,
                        streamingContent = partialContent,
                        retryInfo = null,
                        activeToolCalls = emptyList(),
                        streamingAttachments = emptyList(),
                    )
                    error = if (keyError != null) null else event.message
                }
                comparisonDelegate.endStreaming()
                // Don't auto-drain into a failed turn — hold the queue for the user.
                queueDelegate.pause()
                if (keyError != null) {
                    emitUserKeyError(keyError)
                }
                // If the server already created a conversation, fetch whatever it persisted
                val conversationId = handle.state.conversationId
                if (conversationId != null) {
                    reloadConversation(conversationId)
                }
            }
            is StreamEvent.Retrying -> {
                handle.update {
                    content = content.copy(
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
                handle.update {
                    content = content.copy(activeToolCalls = content.activeToolCalls + newToolCall)
                }
            }
            is StreamEvent.ToolCallComplete -> {
                handle.update {
                    val updated = content.activeToolCalls.map { tc ->
                        if (tc.id == event.toolCallId) {
                            tc.copy(isComplete = true, output = event.output)
                        } else {
                            tc
                        }
                    }
                    content = content.copy(activeToolCalls = updated)
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
                    webSearch = event.webSearch,
                )
                // Office-doc previews (v0.8.6) arrive twice per file_id (pending →
                // ready/failed) — route through the delegate for upsert-by-file_id +
                // poll-while-pending. Ordinary attachments keep the simple append path.
                if (ArtifactType.isOfficePreviewMime(event.type)) {
                    officePreviewDelegate.onAttachment(attachment)
                } else if (attachment.webSearch != null && attachment.toolCallId != null) {
                    // Web-search re-emits an accumulating superset per source processed —
                    // upsert by toolCallId so we keep only the latest (fullest) one rather
                    // than piling up near-duplicate copies for the stream's duration.
                    handle.update {
                        val kept = content.streamingAttachments.filterNot {
                            it.type == ToolConstants.WEB_SEARCH && it.toolCallId == attachment.toolCallId
                        }
                        content = content.copy(streamingAttachments = kept + attachment)
                    }
                } else {
                    handle.update {
                        content = content.copy(streamingAttachments = content.streamingAttachments + attachment)
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
                handle.update {
                    if (content.retryInfo != null) content = content.copy(retryInfo = null)
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
                handle.update { content = content.copy(activeToolCalls = syncedToolCalls) }
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
                handle.update { content = content.copy(contextUsage = event.usage) }
            }
            is StreamEvent.TokenUsageUpdate -> {
                // Per-call provider usage; the gauge denominator comes from the context
                // snapshot, but the breakdown sheet shows Input/Output from this. In-memory only.
                handle.update { content = content.copy(tokenUsage = event.usage) }
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
        val current = handle.state.conversationId
        if (current != null && current != event.conversationId) return
        handle.update { conversation = conversation.copy(conversationTitle = event.title) }
    }

    private fun handleCreated(event: StreamEvent.Created) {
        if (lastErrorWasNetwork) {
            lastErrorWasNetwork = false
            cancelConnectivityObserver()
        }
        handle.update {
            conversation = conversation.copy(conversationId = event.conversationId)
            if (content.retryInfo != null) {
                content = content.copy(retryInfo = null)
            }
        }
        completionDelegate.onConversationCreated(event.conversationId, isNewConversation(), streamOriginAccountId)
    }

    private fun handleFinal(rawEvent: StreamEvent.Final) {
        // A stopped turn arrives as an ordinary `final` frame flagged `aborted` — read it off
        // the event rather than off local stop state, so an abort issued from another client on
        // the same conversation is treated identically.
        val aborted = rawEvent.aborted
        // An aborted frame is poorer than a completed one (skeletal request, no `text`), and we
        // persist it where the web client doesn't — so normalize once here, before anything
        // renders or caches it. See normalizeAbortedFrame.
        val event = if (aborted) rawEvent.normalizeAbortedFrame(handle.state.messages) else rawEvent
        abortRequested = false
        stopStreamingUpdater()
        // The stream has ended: any office-doc attachment still `pending` (its
        // `ready` SSE update may never arrive once the run closes) now falls back
        // to polling GET /api/files/:id/preview. De-duped + bounded in the delegate.
        officePreviewDelegate.onStreamEnded()
        val isComparison = handle.state.comparisonState.isEnabled
        val conversationId = handle.state.conversationId
            ?: event.conversation?.conversationId
        val completedResponseText = if (isComparison) {
            comparisonDelegate.primaryContent()
        } else {
            streamingBuffer.toString()
        }
        // Never auto-read a reply the user just cut off.
        val shouldAutoRead = !isEditOrRegenerate && !aborted
        // Make sure the resolved conversation id is in state for the completion handlers.
        if (conversationId != null) {
            handle.update { conversation = conversation.copy(conversationId = conversationId) }
        }
        if (isComparison) {
            // Comparison reconciles via background reload (no in-memory finalize to fold the
            // streaming-clear into), so clear the single-stream UI fields now.
            handle.update {
                content = content.copy(
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
            originAccount = streamOriginAccountId,
            aborted = aborted,
        )
        // Fallback for degenerate finals: a non-comparison stream that ends with no
        // conversation id (and thus nothing to finalize) never reaches finalizeChatDisplay,
        // so its streaming fields would otherwise stay set. No-op once the in-memory
        // finalize (normal/temp) or the comparison branch above has already cleared them.
        if (handle.state.isStreaming) {
            handle.update {
                content = content.copy(
                    isStreaming = false,
                    streamingContent = "",
                    activeToolCalls = emptyList(),
                    streamingAttachments = emptyList(),
                )
            }
        }
        if (aborted) {
            // A stopped turn must not auto-drain. Re-assert the hold rather than merely skipping
            // the drain: stopGeneration's pause() fires before the abort round-trip completes and
            // no-ops on an empty queue, so a follow-up typed while the turn was winding down would
            // otherwise sit with no drain trigger and no "Send queued" affordance (which renders
            // only for a paused queue).
            queueDelegate.pause()
        } else {
            // Reply finished cleanly: fire the next queued follow-up (if any, and not paused).
            // isStreaming is already false here, so the next send respects the no-Room-write-
            // while-streaming invariant.
            queueDelegate.drainNext()
        }
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
        handle.update { content = content.copy(streamingContent = streamingBuffer.toString()) }
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

    /**
     * Asks the server to stop the in-flight turn, then lets the stream end itself.
     *
     * The abort POST only acks (`{ success, aborted }`) — it does NOT carry the turn. The server
     * ends the run by emitting a normal `final` frame, flagged `aborted`, over the SSE stream we
     * are already collecting, and that frame carries the partial's content parts. So the whole
     * job here is to ask and then get out of the way: [handleFinal] finalizes it through the
     * ordinary completion path (atomic bubble→message swap, id reconciliation), with the
     * stop-specific behavior keyed off the frame's own `aborted` flag.
     *
     * **Do not cancel [streamJob] here.** That was the original bug: killing the collector
     * discarded the very frame that carries the partial, leaving nothing to show and forcing a
     * refetch that raced the server's asynchronous persistence — which is why the stopped reply
     * vanished and only reappeared after enough time had passed elsewhere. The only path that
     * still force-stops locally is [forceStopStream], for when the abort request itself fails and
     * no frame is coming.
     */
    fun stopGeneration() {
        val conversationId = handle.state.conversationId ?: return
        // Re-entry guard: the stream keeps running (and isStreaming stays true) until the
        // aborted final lands, so isStreaming can't distinguish a second tap from a first.
        if (abortRequested) return
        abortRequested = true
        // A manual stop usually means "wait" — hold the queue instead of firing the next item.
        // Re-asserted in handleFinal, since anything queued between here and the final frame
        // arrives after this pause() has already no-opped on an empty queue.
        queueDelegate.pause()
        scope.launch {
            val abortResult = chatRepository.abortChat(
                streamId = conversationId,
                // SECURITY: temp-chat data-at-rest guard — without this the partial the abort
                // route persists gets no expiry. See ChatAbortRequest.isTemporary.
                isTemporary = handle.state.isTemporaryChat,
            )
            if (abortResult is Result.Error) {
                Logger.w(abortResult.exception) { "Failed to abort chat: ${abortResult.message}" }
                // The server never accepted the abort (404 job-not-found, offline, legacy backend
                // with no such route), so no aborted final is coming and the stream would hang in
                // its streaming state. Stop it locally instead.
                forceStopStream(conversationId)
            }
        }
    }

    /**
     * Local stop for when the abort request failed and no `final` frame will arrive. Ends the
     * stream the same way the error paths do — partial preserved in state, queue held, server
     * re-read for whatever it managed to persist.
     */
    private fun forceStopStream(conversationId: String) {
        abortRequested = false
        streamJob?.cancel()
        stopStreamingUpdater()
        if (handle.state.isStreaming) {
            val partialContent = streamingBuffer.toString()
            handle.update {
                content = content.copy(
                    isStreaming = false,
                    streamingContent = partialContent,
                    retryInfo = null,
                    activeToolCalls = emptyList(),
                    streamingAttachments = emptyList(),
                )
            }
        }
        comparisonDelegate.endStreaming(clearContent = true)
        queueDelegate.pause()
        reloadConversation(conversationId)
    }

    fun onPause() {
        wasStreaming = handle.state.isStreaming
        if (wasStreaming) {
            streamJob?.cancel()
            stopStreamingUpdater()
        }
    }

    fun onResume() {
        if (!wasStreaming) return
        wasStreaming = false

        val conversationId = handle.state.conversationId ?: return

        scope.launch {
            try {
                val status = chatRepository.checkStreamStatus(conversationId)
                if (status.active) {
                    handle.update { content = content.copy(isStreaming = true) }
                    resumeStream(conversationId)
                } else {
                    handle.update { content = content.copy(isStreaming = false, streamingContent = "") }
                    reloadConversation(conversationId)
                }
            } catch (e: Exception) {
                Logger.e(e) { "Could not resume stream" }
                handle.update {
                    content = content.copy(
                        isStreaming = false,
                        streamingContent = "",
                    )
                    error = "Could not resume stream"
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
        // Re-capture the origin: a resumed/reconnected stream finalizes under whoever is active now
        // (you can only resume your own conversation), so its writes attribute to that account.
        streamOriginAccountId = activeAccountProvider.currentAccountId()
        abortRequested = false
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
                    handle.update {
                        content = content.copy(
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
        val state = handle.state
        val conversationId = state.conversationId ?: return
        if (state.isStreaming) return

        lastErrorWasNetwork = false
        cancelConnectivityObserver()
        Logger.d { "Network recovered, attempting to resume conversation $conversationId" }

        scope.launch {
            try {
                val status = chatRepository.checkStreamStatus(conversationId)
                if (status.active) {
                    handle.update {
                        content = content.copy(
                            isStreaming = true,
                            retryInfo = null,
                        )
                        error = null
                    }
                    resumeStream(conversationId)
                } else {
                    // Stream expired while offline — reload conversation from server
                    handle.update {
                        error = null
                        content = content.copy(retryInfo = null)
                    }
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
