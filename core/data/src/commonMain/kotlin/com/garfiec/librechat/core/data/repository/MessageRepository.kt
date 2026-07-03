package com.garfiec.librechat.core.data.repository

import com.garfiec.librechat.core.common.identity.AccountId
import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.model.Message
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
    suspend fun refreshMessages(conversationId: String): Result<List<Message>>
    suspend fun updateFeedback(conversationId: String, messageId: String, feedback: String?): Result<Unit>
    suspend fun updateMessageText(conversationId: String, messageId: String, text: String): Result<Unit>
    suspend fun branchMessage(conversationId: String, messageId: String, agentId: String? = null): Result<Message>
}
