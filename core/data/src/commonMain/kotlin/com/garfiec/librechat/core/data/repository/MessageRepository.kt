package com.garfiec.librechat.core.data.repository

import com.garfiec.librechat.core.common.identity.AccountId
import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.model.Message
import com.garfiec.librechat.core.model.MinimalFeedback
import kotlinx.coroutines.flow.Flow

interface MessageRepository {
    fun observeMessages(conversationId: String): Flow<List<Message>>
    suspend fun getMessages(conversationId: String): Result<List<Message>>

    /**
     * Persists [messages] to the local cache only — no network call. Records what a completed
     * stream already delivered (the SSE Final event is authoritative) so reopening shows it,
     * without a redundant `GET /messages` round-trip. The Room flow auto-emits.
     *
     * [originAccount] is the account captured at send time (origin-capture provenance): this write
     * lands after the stream, possibly after an account switch, so the rows are stamped with the
     * originating account rather than the live active one. Null (foreground callers) stamps the live
     * active account. See `resolveWriteAccountId`.
     */
    suspend fun cacheMessages(messages: List<Message>, originAccount: AccountId?)

    /**
     * Re-reads a conversation's messages from the server and **replaces** its cached rows, so
     * server-side deletions disappear locally where [getMessages]' upsert would leave them behind.
     *
     * [originAccount] carries the same origin-capture contract as [cacheMessages], and here it gates
     * the network leg too: a deferred caller whose origin is no longer the live account is skipped
     * rather than sending that account's conversation id to a different server under a different
     * bearer. Deliberately no default — a silent one would let new deferred callers compile with
     * land-time attribution, which is the mis-attribution this parameter exists to prevent. Pass null
     * from foreground callers, where entry *is* land time.
     */
    suspend fun refreshMessages(conversationId: String, originAccount: AccountId?): Result<List<Message>>
    suspend fun updateFeedback(conversationId: String, messageId: String, feedback: MinimalFeedback?): Result<Unit>
    suspend fun updateMessageText(conversationId: String, messageId: String, text: String): Result<Unit>
    suspend fun branchMessage(conversationId: String, messageId: String, agentId: String? = null): Result<Message>
}
