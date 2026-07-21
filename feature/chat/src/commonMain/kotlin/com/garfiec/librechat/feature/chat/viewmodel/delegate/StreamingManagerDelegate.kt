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
import com.garfiec.librechat.feature.chat.util.applyAbortContract
import com.garfiec.librechat.feature.chat.viewmodel.ActiveToolCall
import com.garfiec.librechat.feature.chat.viewmodel.ChatScreenState
import com.garfiec.librechat.feature.chat.viewmodel.RetryInfo
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
    private val treeDelegate: MessageTreeDelegate,
    /** Emits a typed user-provided-key error for one-shot UI surfacing (snackbar + CTA). */
    private val emitUserKeyError: (UserKeyError) -> Unit,
    /** Reloads the conversation from the server (VM-owned Room observer). */
    private val reloadConversation: (String) -> Unit,
    /**
     * Puts an early-aborted (never-persisted) turn's text back into the composer. Lives on the
     * ViewModel because streaming writes are scoped away from the composer slice.
     */
    private val restoreUnsentInput: (String) -> Unit,
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
     * Cleared at every stream-session boundary — [beginStreaming], [resumeStream], [reset],
     * and [endStream] (every reason) — so a pending abort can never suppress Stop on a later stream.
     */
    private var abortRequested = false

    /**
     * Why a stream session ended. Every *event-driven* termination — a Final or Error frame, a
     * failed abort, the watchdog, a resume that found the job gone — funnels through [endStream]
     * with one of these; the reason decides teardown (job cancel, state write, queue policy,
     * reload) in ONE place instead of each exit path hand-copying its own subset.
     *
     * Not covered: a flow that completes with neither Final nor Error (a clean SSE EOF or a 404 on
     * the stream GET). That falls to the `onTerminated` safety net the caller passes to
     * [launchStream], which clears streaming state directly without a reason. Rare, and a known
     * gap in the chokepoint — do not treat [endStream] as the *only* teardown path.
     */
    private sealed interface StreamEndReason {
        /**
         * [handleFinal] already did the rich work (atomic finalize, comparison routing, state
         * writes). This reason is terminal bookkeeping plus queue policy only.
         */
        data class Finalized(val aborted: Boolean) : StreamEndReason

        /** Flow-level exception or in-band [StreamEvent.Error] — the two legacy paths, unified. */
        data class StreamError(val message: String, val isNetwork: Boolean) : StreamEndReason

        /**
         * The abort POST failed (offline, 404, legacy backend) or the aborted final never arrived
         * (watchdog). No frame is coming: stop locally, keep the partial on screen, and do NOT
         * reload — the server may not have persisted yet (it saves only after emitting the frame),
         * and the optimistic user message was never written to Room, so a refetch here can lose
         * both. The next natural open reconciles.
         */
        data object AbortFallback : StreamEndReason

        /** onResume found the server-side job gone after a detached (backgrounded) stream. */
        data object ResumeExpired : StreamEndReason
    }

    /**
     * Monotonic stream-session counter, bumped at every session start ([beginStreaming],
     * [resumeStream]) and at [reset]. [endStream] latches [endedSession] to it so each session
     * ends through exactly one reason: a second end (late watchdog, abort-failure racing a
     * queued Final) is a structural no-op, and an Int counter — not a boolean — means a stale
     * delayed callback from stream N can never tear down stream N+1.
     */
    private var streamSession = 0

    /** The last session [endStream] ran for; see [streamSession]. */
    private var endedSession = -1

    /**
     * Armed when an abort POST is acked: if the aborted `final` frame doesn't land within
     * [ABORT_FINAL_TIMEOUT_MS], ends the stream via [StreamEndReason.AbortFallback] so a dead
     * SSE socket can't wedge the composer in its streaming state until the 120s SSE stall
     * timeout. Cancelled by [endStream] (any reason) and at every session boundary.
     */
    private var abortWatchdogJob: Job? = null

    private fun isSessionEnded() = endedSession == streamSession

    /** Job for the connectivity observer; started lazily only when a network error occurs. */
    private var connectivityJob: Job? = null

    /** True when the current stream is from an edit, regenerate, or continue operation. */
    var isEditOrRegenerate = false
        private set

    /**
     * The messageId minted for THIS turn's optimistic user-message insert (new send, or the
     * edited sibling on an edit-user turn); null when the turn re-submitted a pre-existing
     * persisted message (regenerate / continue / edit-AI). Consumed by the early-abort un-send:
     * only a message this turn invented may be removed.
     */
    private var currentTurnOptimisticUserMessageId: String? = null

    /**
     * Resets the streaming-internal session state (buffer, edit flag, traces) and starts the
     * throttled updater. Does NOT touch UI state — callers that already mutate UI state
     * for their send (e.g. the optimistic-insert path) use this; [prepareForStreaming] wraps
     * it with the standard streaming-field reset.
     */
    fun beginStreaming(isEdit: Boolean, optimisticUserMessageId: String? = null) {
        isEditOrRegenerate = isEdit
        startStreamSession()
        currentTurnOptimisticUserMessageId = optimisticUserMessageId
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
    fun prepareForStreaming(isEdit: Boolean, optimisticUserMessageId: String? = null) {
        handle.update {
            content = content.copy(
                isStreaming = true,
                streamingContent = "",
                activeToolCalls = emptyList(),
                streamingAttachments = emptyList(),
            )
            error = null
        }
        beginStreaming(isEdit, optimisticUserMessageId)
    }

    /**
     * Cancels any in-flight stream and launches collection of [flow]. [onTerminated] runs
     * after collection completes (success, error, or normal end) — the send paths use it as
     * a safety net for flows that end without a Final/Error event.
     *
     * Ordering contract: callers must have called [beginStreaming]/[prepareForStreaming] first
     * (all current callers do) — that is what bumps [streamSession], so a stale [endStream]
     * from the previous stream can no longer touch this one.
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
        startStreamSession()
        stopStreamingUpdater()
        streamingBuffer.clear()
        streamingBufferDirty = false
    }

    /**
     * Session boundary: invalidates any pending [endStream] callbacks (watchdog, delayed abort
     * failure) from the previous stream and clears the per-session stop state.
     */
    private fun startStreamSession() {
        streamSession++
        abortWatchdogJob?.cancel()
        abortWatchdogJob = null
        abortRequested = false
        // A resumed stream re-enters without beginStreaming's assignment; never let a previous
        // turn's optimistic id leak into it (the un-send would remove the wrong message).
        currentTurnOptimisticUserMessageId = null
    }

    private suspend fun collectStreamSafely(stream: Flow<StreamEvent>) {
        try {
            stream.collect { event -> handleStreamEvent(event) }
        } catch (e: CancellationException) {
            throw e // Never swallow cancellation
        } catch (e: Exception) {
            Logger.e(e) { "Stream collection failed" }
            endStream(StreamEndReason.StreamError(e.message ?: "Chat request failed", isNetwork = false))
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
                endStream(StreamEndReason.StreamError(event.message, event.isNetworkError))
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
        // The session already ended through another reason (e.g. a failed-abort AbortFallback
        // latched while this Final sat in the dispatcher queue) — a second finalize would
        // double-apply state and queue policy.
        if (isSessionEnded()) return
        // A stopped turn arrives as an ordinary `final` frame flagged `aborted` — read it off
        // the event rather than off local stop state, so an abort issued from another client on
        // the same conversation is treated identically.
        val aborted = rawEvent.aborted
        // Flush the tail of the buffer before it is read below; endStream repeats this
        // idempotently at the end.
        stopStreamingUpdater()
        // The stream has ended: any office-doc attachment still `pending` (its
        // `ready` SSE update may never arrive once the run closes) now falls back
        // to polling GET /api/files/:id/preview. De-duped + bounded in the delegate.
        officePreviewDelegate.onStreamEnded()
        // Early abort: the Stop landed before the server's `created` milestone, so NOTHING was
        // persisted — not even the user message. Un-send the turn (remove the optimistic bubble,
        // hand its text back to the composer) instead of finalizing: any bubble kept here would
        // be silently dropped by the next sync. Mirrors the web client's early-abort handling.
        // No completionDelegate.onFinal — there is no conversation save, cache, title, or TTS
        // for a turn that never existed.
        if (aborted && rawEvent.earlyAbort) {
            val unsentText = currentTurnOptimisticUserMessageId
                ?.let { id -> handle.state.messages.firstOrNull { it.messageId == id }?.text }
            treeDelegate.unsendOptimisticTurn(currentTurnOptimisticUserMessageId)
            unsentText?.takeIf { it.isNotBlank() }?.let(restoreUnsentInput)
            endStream(StreamEndReason.Finalized(aborted = true))
            return
        }
        // Make the aborted frame agree with what the server persisted — rebuild the missing
        // `text` for a persisted response, DROP a response the server never saved — once, here,
        // before anything renders or caches it. See applyAbortContract.
        val event = if (aborted) rawEvent.applyAbortContract() else rawEvent
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
        // Must be the LAST statement: the drain inside endStream sends the next queued item,
        // and doSendWithSpec silently no-ops while isStreaming is still true — so the finalize
        // above has to have cleared it first.
        endStream(StreamEndReason.Finalized(aborted))
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
     * vanished and only reappeared after enough time had passed elsewhere. The only local stop
     * left is `endStream(AbortFallback)`, for when the abort POST fails or the frame never
     * arrives (watchdog).
     *
     * Works even before the `created` milestone assigns a conversation id: the abort route falls
     * back to one of the caller's active jobs when no id resolves, so the request goes out with a
     * null key rather than silently doing nothing.
     *
     * Caveat with concurrent jobs: the server's fallback picks the *oldest* active job (the store
     * iterates in insertion order and takes the first), not the newest, and [ChatAbortResponse]
     * discards the `aborted` id the route returns — so the client cannot tell which job it hit. If
     * a second stream is live server-side (e.g. one left running in the background), a null-key Stop
     * can abort the wrong one; the still-running local stream then only stops when its watchdog
     * fires. This is a known gap, tracked for a follow-up, not a guarantee.
     */
    fun stopGeneration() {
        // Nothing to stop: no live collector and no streaming state (a dead screen's Stop must
        // not abort-by-fallback some other conversation's job).
        if (streamJob?.isActive != true && !handle.state.isStreaming) return
        // Re-entry guard: the stream keeps running (and isStreaming stays true) until the
        // aborted final lands, so isStreaming can't distinguish a second tap from a first.
        if (abortRequested) return
        abortRequested = true
        // Pin the session so the delayed failure/watchdog callbacks below can never tear down
        // a stream that started after this Stop.
        val session = streamSession
        // A manual stop usually means "wait" — hold the queue instead of firing the next item.
        // Re-asserted in endStream, since anything queued between here and the final frame
        // arrives after this pause() has already no-opped on an empty queue.
        queueDelegate.pause()
        scope.launch {
            val abortResult = chatRepository.abortChat(
                streamId = handle.state.conversationId,
                // SECURITY: temp-chat data-at-rest guard — without this the partial the abort
                // route persists gets no expiry. See ChatAbortRequest.isTemporary.
                isTemporary = handle.state.isTemporaryChat,
            )
            if (abortResult is Result.Error) {
                Logger.w(abortResult.exception) { "Failed to abort chat: ${abortResult.message}" }
                // The server never accepted the abort (404 job-not-found, offline, legacy backend
                // with no such route), so no aborted final is coming and the stream would hang in
                // its streaming state. Stop it locally instead.
                endStream(StreamEndReason.AbortFallback, session)
            } else {
                armAbortWatchdog(session)
            }
        }
    }

    /**
     * The abort was acked, so the aborted final *should* arrive within a beat — but the SSE
     * socket can be silently dead (cell handoff, NAT timeout). Without this, `isStreaming`
     * stays true and [abortRequested] suppresses every further Stop tap until the 120s SSE
     * stall timeout. See [abortWatchdogJob].
     */
    private fun armAbortWatchdog(session: Int) {
        abortWatchdogJob?.cancel()
        abortWatchdogJob = scope.launch {
            delay(ABORT_FINAL_TIMEOUT_MS)
            Logger.w { "Aborted final never arrived; stopping the stream locally" }
            endStream(StreamEndReason.AbortFallback, session)
        }
    }

    /**
     * The stream-termination chokepoint for every *event-driven* end — clean or aborted Final,
     * error, failed abort, watchdog, resume-found-expired — so teardown steps can't drift apart
     * per exit path again. The one exception is a flow that ends with neither Final nor Error,
     * handled by the `onTerminated` safety net (see [StreamEndReason]); keep new teardown here.
     *
     * Latched per session: runs at most once for [session], and never for a stale session
     * (see [streamSession]). [StreamEndReason.Finalized] deliberately writes no state — the
     * atomic finalize in `finalizeChatDisplay` (and handleFinal's degenerate fallback) owns
     * that, preserving the no-completion-flash invariant (#169).
     */
    private fun endStream(reason: StreamEndReason, session: Int = streamSession) {
        if (session != streamSession) return
        if (endedSession == session) return
        endedSession = session
        abortWatchdogJob?.cancel()
        abortWatchdogJob = null
        abortRequested = false
        when (reason) {
            is StreamEndReason.Finalized -> {
                stopStreamingUpdater()
                if (reason.aborted) {
                    // A stopped turn must not auto-drain. Re-assert the hold rather than merely
                    // skipping the drain: stopGeneration's pause() fired before the abort
                    // round-trip completed and no-ops on an empty queue, so a follow-up typed
                    // while the turn was winding down would otherwise sit with no drain trigger
                    // and no "Send queued" affordance (which renders only for a paused queue).
                    queueDelegate.pause()
                } else {
                    // Reply finished cleanly: fire the next queued follow-up (if any, and not
                    // paused). isStreaming is already false here, so the next send respects the
                    // no-Room-write-while-streaming invariant.
                    queueDelegate.drainNext()
                }
            }
            is StreamEndReason.StreamError -> {
                stopStreamingUpdater()
                // Track network errors so auto-reconnect can kick in when connectivity returns.
                lastErrorWasNetwork = reason.isNetwork
                if (reason.isNetwork) {
                    startConnectivityObserver()
                }
                // A typed user-provided-key error surfaces as a snackbar with a Settings CTA
                // instead of the generic error banner (no double-surfacing).
                val keyError = parseUserKeyError(reason.message)
                // Preserve partial content so users can read/copy what was received.
                val partialContent = streamingBuffer.toString()
                handle.update {
                    content = content.copy(
                        isStreaming = false,
                        streamingContent = partialContent,
                        retryInfo = null,
                        activeToolCalls = emptyList(),
                        streamingAttachments = emptyList(),
                    )
                    error = if (keyError != null) null else reason.message
                }
                comparisonDelegate.endStreaming()
                // Don't auto-drain into a failed turn — hold the queue for the user.
                queueDelegate.pause()
                if (keyError != null) {
                    emitUserKeyError(keyError)
                }
                // If the server already created a conversation, fetch whatever it persisted.
                handle.state.conversationId?.let(reloadConversation)
            }
            is StreamEndReason.AbortFallback -> {
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
                // Keep the partial panes too — same intent as preserving streamingContent above.
                comparisonDelegate.endStreaming(clearContent = false)
                queueDelegate.pause()
                // NO reload — see the reason's KDoc: a refetch here races the server's
                // post-frame persistence and can lose both halves of the turn.
            }
            is StreamEndReason.ResumeExpired -> {
                streamJob?.cancel()
                stopStreamingUpdater()
                handle.update {
                    content = content.copy(isStreaming = false, streamingContent = "")
                }
                comparisonDelegate.endStreaming(clearContent = true)
                // Hold rather than drain: a resume gesture must never auto-fire a queued send.
                queueDelegate.pause()
                // Safe here, unlike the abort paths: "expired" means the job completed and was
                // cleaned up in the past — the resume gesture arrives at human latency, well
                // clear of the emit-then-persist window.
                handle.state.conversationId?.let(reloadConversation)
            }
        }
    }

    fun onPause() {
        wasStreaming = handle.state.isStreaming
        if (!wasStreaming) return
        // A Stop is in flight: the collector is carrying the aborted final that holds the
        // partial, the ViewModel scope survives backgrounding, and the watchdog is armed.
        // Cancelling here would discard the frame — the stopped reply would come back blank
        // (the exact bug this design exists to fix).
        if (abortRequested) return
        // Normal streaming: detach as before (SSE over a backgrounded socket is unreliable);
        // onResume reconciles via the server-side stream status.
        streamJob?.cancel()
        stopStreamingUpdater()
    }

    fun onResume() {
        if (!wasStreaming) return
        wasStreaming = false
        // The session ended while backgrounded (the aborted final landed in the still-live
        // collector, or an error tore down): state is already finalized — touch nothing.
        if (isSessionEnded()) return
        // Abort pending with a live collector: the final is still coming and the watchdog
        // guards against it never arriving. Leave the stream alone.
        if (abortRequested && streamJob?.isActive == true) return
        val conversationId = handle.state.conversationId ?: return
        scope.launch {
            try {
                val status = chatRepository.checkStreamStatus(conversationId)
                if (status.active) {
                    handle.update { content = content.copy(isStreaming = true) }
                    resumeStream(conversationId)
                } else {
                    endStream(StreamEndReason.ResumeExpired)
                }
            } catch (e: Exception) {
                Logger.e(e) { "Could not resume stream" }
                endStream(StreamEndReason.ResumeExpired)
                handle.update { error = "Could not resume stream" }
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
        startStreamSession()
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

        /**
         * How long after an acked abort to wait for the aborted `final` frame before stopping
         * locally. Generous vs the observed sub-second emit→deliver latency, tight vs the 120s
         * SSE stall timeout that is otherwise the only recovery.
         */
        const val ABORT_FINAL_TIMEOUT_MS = 15_000L
    }
}
