package com.garfiec.librechat.feature.chat.viewmodel.delegate

import co.touchlab.kermit.Logger
import com.garfiec.librechat.core.common.identity.AccountId
import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.common.result.getOrNull
import com.garfiec.librechat.core.data.repository.ConversationRepository
import com.garfiec.librechat.core.data.repository.DraftRepository
import com.garfiec.librechat.core.data.repository.MessageRepository
import com.garfiec.librechat.core.logging.Diag
import com.garfiec.librechat.core.model.Message
import com.garfiec.librechat.core.model.StreamEvent
import com.garfiec.librechat.feature.chat.util.NEW_CHAT_DRAFT_KEY
import com.garfiec.librechat.feature.chat.util.resolvedResponseMessage
import com.garfiec.librechat.feature.chat.viewmodel.NewChatSelectionHandoff
import com.garfiec.librechat.feature.chat.viewmodel.PLACEHOLDER_CONVERSATION_TITLE
import com.garfiec.librechat.feature.chat.viewmodel.SendCompletionHandle
import com.garfiec.librechat.feature.chat.viewmodel.shouldRequestTitleGeneration
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Owns what happens when a chat send reaches the server: the `created` and `final`
 * SSE milestones and everything they trigger that is NOT raw stream plumbing —
 * draft migration onto the new conversation id, the new-chat selection handoff +
 * deferred navigation, persisting the final conversation (with the temp-chat
 * data-at-rest guard), title generation, and conversation-model re-derivation.
 *
 * `ChatViewModel`'s stream handler invokes [onConversationCreated] / [onFinal] with the
 * streaming-derived inputs (the new id, the completed text); the streaming-internal
 * cleanup (buffer flush, connectivity reset) stays on the caller's side.
 */
class SendCompletionDelegate(
    private val handle: SendCompletionHandle,
    private val conversationRepository: ConversationRepository,
    private val messageRepository: MessageRepository,
    private val draftRepository: DraftRepository,
    private val modelDelegate: ModelSelectionDelegate,
    private val treeDelegate: MessageTreeDelegate,
    private val tts: PlatformTts,
    private val selectionHandoff: NewChatSelectionHandoff,
    /** Reloads the conversation from the server (VM-owned Room observer). */
    private val reloadConversation: (String) -> Unit,
) {

    private var titleGenerationRequested = false

    /**
     * Handles the `created` milestone: migrates the new-chat draft onto the real
     * conversation id, stages the sent selection for the about-to-be-created Chat(id) VM,
     * arms deferred navigation, and eagerly caches the conversation so it lands in the
     * list even if the stream later fails.
     */
    fun onConversationCreated(conversationId: String, isNewConversation: Boolean, originAccount: AccountId?) {
        // SECURITY: temp-chat data-at-rest guard. Temporary chats still navigate to a
        // Chat(id) so Back returns to the landing screen (they're a real, streaming conversation
        // for the session) — but they must never be written to Room. Two Room-write paths in this
        // landing VM are gated on this flag: the draft migration below (persists the composer draft
        // keyed to the real id) and the eager cache-warm at the end (GETs + upserts the
        // conversation — the write that would otherwise surface a temp chat in history). Temp-ness reaches the
        // new Chat(id) VM via the Chat route's isTemporary flag (durable across process death), so
        // that VM starts temp-aware and skips its own loadConversation / loadConversationModel
        // upserts — see ChatViewModel.init and Navigator.navigateToChat.
        val isTemporary = handle.state.isTemporaryChat
        if (isNewConversation) {
            if (!isTemporary) {
                handle.scope.launch {
                    val existingDraft = draftRepository.getDraft(NEW_CHAT_DRAFT_KEY)
                    if (existingDraft != null) {
                        draftRepository.saveDraft(conversationId, existingDraft)
                        draftRepository.deleteDraft(NEW_CHAT_DRAFT_KEY)
                    }
                }
            }
            // Stage the selection we actually sent so the about-to-be-created Chat(id) VM can
            // apply it directly instead of re-deriving it from a GET that races the server's
            // unawaited conversation save. Keyed by id, so a deferred nav (comparison-mode
            // branch) still picks it up. See NewChatSelectionHandoff.
            val sent = handle.state
            // Hand off the optimistic user message too, so the about-to-be-created Chat(id) VM
            // can keep it on screen during the resumed stream — the server doesn't persist the
            // request message until the reply completes. See NewChatSelectionHandoff.
            val optimisticUserMessage = sent.messages.lastOrNull { it.isCreatedByUser }
            selectionHandoff.put(conversationId, sent.selectedEndpoint, sent.selectedModel, optimisticUserMessage)
            val snapshot = handle.state
            if (snapshot.pendingNavigationConversationId == null && !snapshot.comparisonState.isEnabled) {
                handle.update {
                    conversation = conversation.copy(pendingNavigationConversationId = conversationId)
                }
            }
        }
        // Eagerly fetch and cache the conversation the server just created, so it appears in the
        // conversation list even if the stream fails later — but never for temp chats (see guard).
        if (!isTemporary) {
            handle.scope.launch {
                conversationRepository.getConversation(conversationId, originAccount)
            }
        }
    }

    /**
     * Handles the `final` milestone (single-stream and comparison alike): re-derives the
     * conversation model if it never resolved, persists the final conversation (unless
     * temporary), drives the display (Room reload for normal chats, in-memory finalize for
     * temp chats), kicks off title generation, and auto-reads the response.
     *
     * @param completedResponseText the streamed response text, for auto-read.
     * @param shouldAutoRead false for edit/regenerate/continue (no auto-TTS).
     * @param aborted true when the stream ended because the turn was stopped. The frame is a
     *   deliberately poorer final (stub `conversation`, hardcoded `New Chat` title, no `text`),
     *   so the conversation save is skipped and the title is re-read from the server rather than
     *   generated — see the call sites below.
     */
    fun onFinal(
        event: StreamEvent.Final,
        conversationId: String?,
        completedResponseText: String,
        shouldAutoRead: Boolean,
        isNewConversation: Boolean,
        isHandedOffNewChat: Boolean,
        isComparison: Boolean,
        originAccount: AccountId?,
        aborted: Boolean = false,
    ) {
        val finalConversation = event.conversation
        // Belt-and-braces: if no handoff seeded the selection and the initial GET 404'd
        // against the created-before-save race, the conversation model is still unresolved.
        // The Final event carries the authoritative conversation, so re-derive from it here
        // rather than leaving a fallback guess on screen.
        if (!modelDelegate.conversationModelResolved && finalConversation != null) {
            val applied = modelDelegate.applyConversationModel(finalConversation)
            Diag.i(
                tag = "ModelSel",
                attrs = mapOf("applied" to applied.toString()),
            ) { "handleFinal re-derived conversation model" }
        }
        // SECURITY: do not remove — temp-chat data-at-rest guard.
        // Temporary chats are kept out of normal history — the server excludes
        // them from the conversation list, so don't cache them to Room either (it would
        // leak a temp chat into the local list the server hides).
        val isTemporary = handle.state.isTemporaryChat || finalConversation?.isTemporary == true
        // An aborted final carries only a stub conversation — `{ conversationId }` plus a
        // hardcoded "New Chat" title — not the real record. Caching it would overwrite the good
        // row (title included) with placeholder data, which the web client never notices because
        // it has no local store. Skip the save; the title refresh below re-reads the real one.
        if (finalConversation?.conversationId != null && !isTemporary && !aborted) {
            handle.scope.launch {
                conversationRepository.saveConversation(finalConversation, originAccount)
            }
        }
        if (conversationId != null) {
            if (isTemporary) {
                // SECURITY: do not remove — temp-chat data-at-rest guard.
                // Temp chats are never persisted: don't round-trip through the Room
                // read-through (which would upsert the message rows to disk). Drive the
                // display from the final event in memory instead. Title generation is
                // also skipped server-side for temp chats, so there's nothing to refresh.
                treeDelegate.finalizeChatDisplay(event)
            } else {
                if (isComparison && !aborted) {
                    // A comparison persists as ONE response message whose content parts carry
                    // per-agent attribution (added agent suffixed ____N). The Final event doesn't
                    // model that split, so reload from the server to materialize the attributed
                    // message; ChatContent then renders each pane from its own parts, and reopen
                    // rehydration reads the same attribution. (The live panes were already
                    // finalized in memory by comparisonDelegate.onFinal.)
                    treeDelegate.markSettled(event.resolvedResponseMessage()?.messageId)
                    reloadConversation(conversationId)
                } else {
                    // The Final event is authoritative (it echoes the server-adopted ids, PR
                    // #139), so finalize in memory for a gap-free completion and cache the turn
                    // locally. No `GET /messages` round-trip: it only re-fetched invisible
                    // canonical fields (refreshed anyway on the next open) while re-rendering
                    // the list with value-different instances — the completion flash.
                    //
                    // A STOPPED comparison turn takes this path too: the reload above would race
                    // the server's post-frame persistence (it emits the aborted frame BEFORE
                    // saving), returning the conversation without the partial — the vanishing-
                    // partial bug in comparison clothes. The frame's response already passed
                    // applyAbortContract, so it is either genuinely persisted or absent; the
                    // frozen pane buffers (comparisonDelegate.onFinal) cover rendering, and
                    // reopen rehydration reads the cached attributed tail.
                    val finalizedTurn = treeDelegate.finalizeChatDisplay(event)
                    // Cache unconditionally: applyAbortContract already dropped any response the
                    // server didn't persist, and on a non-early abort the request message IS
                    // server-persisted — caching it is required, since this is the only write
                    // that persists the optimistic user message at all.
                    cacheTurn(finalizedTurn, originAccount)
                }
                if (aborted) {
                    // Do NOT run gen_title on a stopped turn. With the default `immediate` title
                    // timing the server already fired title generation in parallel with the reply
                    // and keeps a title that finished before the Stop — so the title may well
                    // exist already. With `final` timing the server skips it outright and the
                    // long-poll would just 404 after its backoff, while latching
                    // titleGenerationRequested would suppress a later genuine attempt.
                    // Either way the right move is to re-read, not to generate — and network-first,
                    // since the cached row still holds the "New Chat" placeholder that a
                    // cache-first read would happily return.
                    refreshTitleFromServer(conversationId, originAccount)
                } else {
                    val shouldGenerate = shouldRequestTitleGeneration(
                        isNewConversation = isNewConversation,
                        isHandedOffNewChat = isHandedOffNewChat,
                        currentTitle = handle.state.conversationTitle,
                        alreadyRequested = titleGenerationRequested,
                    )
                    if (shouldGenerate) {
                        titleGenerationRequested = true
                        generateAndSetTitle(conversationId, originAccount)
                    } else {
                        refreshConversationTitle(conversationId, originAccount)
                    }
                }
            }
        }
        if (shouldAutoRead && completedResponseText.isNotBlank()) {
            tts.maybeAutoReadResponse(completedResponseText)
        }
    }

    /**
     * Records the just-finalized [turn] to the local cache only — no network. The optimistic
     * user message was never written to Room during streaming (the streaming-anchor invariant
     * forbids mid-stream writes), so this is what keeps it from being lost on reopen. The
     * resulting Room emission conflates away via instance-stabilization in loadConversation.
     */
    private fun cacheTurn(turn: List<Message>, originAccount: AccountId?) {
        if (turn.isEmpty()) return
        handle.scope.launch {
            runCatching { messageRepository.cacheMessages(turn, originAccount) }
                .onFailure { Logger.e(it) { "Failed to cache final messages for the completed turn" } }
        }
    }

    /**
     * Network-first title re-read, for the stopped-turn path. [refreshConversationTitle]'s
     * cache-first `getConversation` can't be reused here: the aborted turn never wrote a real
     * conversation row, so the cache still holds the "New Chat" placeholder and would satisfy the
     * read without ever asking the server. `refreshConversation` also upserts, so a title found
     * this way propagates to the Room-observing conversation list.
     *
     * Retried past the placeholder, and never a downgrade: the server's title save is gated on
     * the request's unwind, so a read fired the instant the aborted final lands races it and can
     * return "New Chat" — the same emit-then-persist race as the messages, one layer up. On-device
     * this showed as a stopped first turn's title reverting from the TitleUpdate-delivered title
     * to the placeholder (and the racing read's upsert made it sticky in the drawer). So: apply
     * only a real title; on a placeholder, retry after a beat — a retry that finds the real title
     * also re-upserts, healing the row the first read poisoned. If every read returns the
     * placeholder (title generation genuinely cancelled in-flight by the Stop), the in-memory
     * title — possibly already set by TitleUpdate — is left untouched.
     */
    private fun refreshTitleFromServer(conversationId: String, originAccount: AccountId?) {
        handle.scope.launch {
            repeat(ABORT_TITLE_READ_ATTEMPTS) { attempt ->
                if (attempt > 0) delay(ABORT_TITLE_RETRY_DELAY_MS)
                val title = conversationRepository.refreshConversation(conversationId, originAccount)
                    .getOrNull()?.title
                if (!title.isNullOrBlank() && title != PLACEHOLDER_CONVERSATION_TITLE) {
                    handle.update { conversation = conversation.copy(conversationTitle = title) }
                    return@launch
                }
            }
        }
    }

    private fun refreshConversationTitle(conversationId: String, originAccount: AccountId?) {
        handle.scope.launch {
            val fetched = conversationRepository.getConversation(conversationId, originAccount).getOrNull()
                ?: return@launch
            handle.update { conversation = conversation.copy(conversationTitle = fetched.title) }
        }
    }

    private fun generateAndSetTitle(conversationId: String, originAccount: AccountId?) {
        handle.scope.launch {
            when (val result = conversationRepository.generateTitle(conversationId, originAccount)) {
                is Result.Success -> {
                    handle.update { conversation = conversation.copy(conversationTitle = result.data) }
                }
                is Result.Error -> {
                    Logger.d { "Title generation failed for $conversationId: ${result.message}" }
                    // The gen_title long-poll can miss even though the server generated and
                    // persisted a title (404 after its backoff window, or another client
                    // consumed the one-shot cache). Fetch network-first: the cached row
                    // still holds the "New Chat" placeholder, so cache-first getConversation
                    // could never observe the title, and refreshConversation's upsert also
                    // propagates it to the Room-observing conversation list.
                    conversationRepository.refreshConversation(conversationId, originAccount).getOrNull()?.let { fetched ->
                        handle.update { conversation = conversation.copy(conversationTitle = fetched.title) }
                    }
                }
                is Result.Loading -> { /* no-op */ }
            }
        }
    }

    private companion object {
        /**
         * Total reads of the aborted-turn title, first one immediate. The server's title save
         * completes at the request's unwind — typically well under a second after the aborted
         * final — so one or two spaced retries comfortably outlast the race without polling.
         */
        const val ABORT_TITLE_READ_ATTEMPTS = 3
        const val ABORT_TITLE_RETRY_DELAY_MS = 2_000L
    }
}
