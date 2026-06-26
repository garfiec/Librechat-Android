package com.garfiec.librechat.feature.chat.viewmodel

import com.garfiec.librechat.core.model.Message

/**
 * In-process, single-slot handoff of the model selection from the NewChat landing
 * ViewModel to the freshly-created Chat(id) ViewModel.
 *
 * Why this exists: navigation to `Chat(id)` fires at the `created` SSE event, but the
 * server emits `created` BEFORE it has persisted the conversation (the save is
 * unawaited). The new VM's `loadConversationModel` GET therefore races that save and
 * can 404, leaving the selection to be re-derived from a fallback guess — which is how
 * the wrong model ends up displayed (and, worse, used on a follow-up send). The landing
 * VM already knows exactly what it sent, so it hands that off here instead of forcing
 * the new VM to re-read it from a racy server endpoint.
 *
 * Single-slot by design: only one new-chat → Chat(id) transition can be in flight at a
 * time. [take] returns the entry only when the conversationId matches, so a stale entry
 * (e.g. the transition never completed) can never be mis-applied to a different chat.
 *
 * A successful [take] also tells the new VM its conversation was just created in this
 * session (`isHandedOffNewChat`): the landing VM is reset at navigation, so the Chat(id)
 * VM is the one that sees the first stream's Final and must run new-chat-only work like
 * title generation, even though its `isNewConversation` is false.
 *
 * Threading: both the [put] (in `handleCreated`) and [take] (in `ChatViewModel.init`)
 * call sites run on the ViewModel's main-dispatcher scope, so no locking is needed.
 * It also self-expires across process death — the slot is empty on restart, and by then
 * the conversation GET succeeds anyway (the race window is milliseconds). Losing the
 * just-created signal that way is accepted: the server generates and persists the title
 * on its own regardless of the client's gen_title long-poll, so the next conversation
 * list sync (loadNextPage) picks it up — the title is briefly stale, not lost.
 */
class NewChatSelectionHandoff {

    data class Selection(
        val conversationId: String,
        val endpoint: String,
        val model: String?,
        /**
         * The optimistic user message the landing VM minted and sent (its id is adopted by the
         * server, PR #139). Handed off because the server persists the request message only when
         * the reply finishes (see `agents/request.js`) — so until then the just-created Chat(id)
         * VM's `getMessages` returns no user message, and the optimistic copy lived only in the
         * now-reset landing VM. Seeding it keeps the user's own message on screen for the whole
         * resumed stream instead of vanishing until completion.
         */
        val optimisticUserMessage: Message? = null,
    )

    private var pending: Selection? = null

    fun put(conversationId: String, endpoint: String, model: String?, optimisticUserMessage: Message? = null) {
        pending = Selection(conversationId, endpoint, model, optimisticUserMessage)
    }

    /** Returns and clears the pending selection only when it was staged for [conversationId]. */
    fun take(conversationId: String): Selection? {
        val current = pending ?: return null
        if (current.conversationId != conversationId) return null
        pending = null
        return current
    }
}
