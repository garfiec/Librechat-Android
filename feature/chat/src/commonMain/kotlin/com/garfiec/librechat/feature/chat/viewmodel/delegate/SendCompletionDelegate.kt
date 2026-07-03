package com.garfiec.librechat.feature.chat.viewmodel.delegate

import co.touchlab.kermit.Logger
import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.common.result.getOrNull
import com.garfiec.librechat.core.data.repository.ConversationRepository
import com.garfiec.librechat.core.data.repository.DraftRepository
import com.garfiec.librechat.core.data.repository.MessageRepository
import com.garfiec.librechat.core.logging.Diag
import com.garfiec.librechat.core.model.Message
import com.garfiec.librechat.core.model.StreamEvent
import com.garfiec.librechat.feature.chat.util.NEW_CHAT_DRAFT_KEY
import com.garfiec.librechat.feature.chat.viewmodel.ChatStateHandle
import com.garfiec.librechat.feature.chat.viewmodel.NewChatSelectionHandoff
import com.garfiec.librechat.feature.chat.viewmodel.shouldRequestTitleGeneration
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
    private val stateHandle: ChatStateHandle,
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
    fun onConversationCreated(conversationId: String, isNewConversation: Boolean) {
        // SECURITY: temp-chat data-at-rest guard. Temporary chats still navigate to a
        // Chat(id) so Back returns to the landing screen (they're a real, streaming conversation
        // for the session) — but they must never be written to Room. Two Room-write paths in this
        // landing VM are gated on this flag: the draft migration below (persists the composer draft
        // keyed to the real id) and the eager cache-warm at the end (GETs + upserts the
        // conversation — the write that would otherwise surface a temp chat in history). Temp-ness reaches the
        // new Chat(id) VM via the Chat route's isTemporary flag (durable across process death), so
        // that VM starts temp-aware and skips its own loadConversation / loadConversationModel
        // upserts — see ChatViewModel.init and Navigator.navigateToChat.
        val isTemporary = stateHandle.state.isTemporaryChat
        if (isNewConversation) {
            if (!isTemporary) {
                stateHandle.scope.launch {
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
            val sent = stateHandle.state
            // Hand off the optimistic user message too, so the about-to-be-created Chat(id) VM
            // can keep it on screen during the resumed stream — the server doesn't persist the
            // request message until the reply completes. See NewChatSelectionHandoff.
            val optimisticUserMessage = sent.messages.lastOrNull { it.isCreatedByUser }
            selectionHandoff.put(conversationId, sent.selectedEndpoint, sent.selectedModel, optimisticUserMessage)
            stateHandle.update {
                if (pendingNavigationConversationId == null && !comparisonState.isEnabled) {
                    copy(pendingNavigationConversationId = conversationId)
                } else {
                    this
                }
            }
        }
        // Eagerly fetch and cache the conversation the server just created, so it appears in the
        // conversation list even if the stream fails later — but never for temp chats (see guard).
        if (!isTemporary) {
            stateHandle.scope.launch {
                conversationRepository.getConversation(conversationId)
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
     */
    fun onFinal(
        event: StreamEvent.Final,
        conversationId: String?,
        completedResponseText: String,
        shouldAutoRead: Boolean,
        isNewConversation: Boolean,
        isHandedOffNewChat: Boolean,
        isComparison: Boolean,
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
        val isTemporary = stateHandle.state.isTemporaryChat || finalConversation?.isTemporary == true
        if (finalConversation?.conversationId != null && !isTemporary) {
            stateHandle.scope.launch {
                conversationRepository.saveConversation(finalConversation)
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
                if (isComparison) {
                    // Comparison's two responses are sibling messages the Final event doesn't
                    // fully model, so keep the server reconcile to materialize both into the
                    // tree (the panes were already finalized by comparisonDelegate.onFinal).
                    reloadConversation(conversationId)
                } else {
                    // The Final event is authoritative (it echoes the server-adopted ids, PR
                    // #139), so finalize in memory for a gap-free completion and cache the turn
                    // locally. No `GET /messages` round-trip: it only re-fetched invisible
                    // canonical fields (refreshed anyway on the next open) while re-rendering
                    // the list with value-different instances — the completion flash.
                    val finalizedTurn = treeDelegate.finalizeChatDisplay(event)
                    cacheTurn(finalizedTurn)
                }
                val shouldGenerate = shouldRequestTitleGeneration(
                    isNewConversation = isNewConversation,
                    isHandedOffNewChat = isHandedOffNewChat,
                    currentTitle = stateHandle.state.conversationTitle,
                    alreadyRequested = titleGenerationRequested,
                )
                if (shouldGenerate) {
                    titleGenerationRequested = true
                    generateAndSetTitle(conversationId)
                } else {
                    refreshConversationTitle(conversationId)
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
    private fun cacheTurn(turn: List<Message>) {
        if (turn.isEmpty()) return
        stateHandle.scope.launch {
            runCatching { messageRepository.cacheMessages(turn) }
                .onFailure { Logger.e(it) { "Failed to cache final messages for the completed turn" } }
        }
    }

    private fun refreshConversationTitle(conversationId: String) {
        stateHandle.scope.launch {
            val conversation = conversationRepository.getConversation(conversationId).getOrNull() ?: return@launch
            stateHandle.update { copy(conversationTitle = conversation.title) }
        }
    }

    private fun generateAndSetTitle(conversationId: String) {
        stateHandle.scope.launch {
            when (val result = conversationRepository.generateTitle(conversationId)) {
                is Result.Success -> {
                    stateHandle.update { copy(conversationTitle = result.data) }
                }
                is Result.Error -> {
                    Logger.d { "Title generation failed for $conversationId: ${result.message}" }
                    // The gen_title long-poll can miss even though the server generated and
                    // persisted a title (404 after its backoff window, or another client
                    // consumed the one-shot cache). Fetch network-first: the cached row
                    // still holds the "New Chat" placeholder, so cache-first getConversation
                    // could never observe the title, and refreshConversation's upsert also
                    // propagates it to the Room-observing conversation list.
                    conversationRepository.refreshConversation(conversationId).getOrNull()?.let { conversation ->
                        stateHandle.update { copy(conversationTitle = conversation.title) }
                    }
                }
                is Result.Loading -> { /* no-op */ }
            }
        }
    }
}
