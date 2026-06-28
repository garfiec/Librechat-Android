package com.garfiec.librechat.core.data.repository

import co.touchlab.kermit.Logger
import com.garfiec.librechat.core.common.identity.ActiveAccountProvider
import com.garfiec.librechat.core.common.identity.currentAccountId
import com.garfiec.librechat.core.common.identity.flatMapAccountOrEmpty
import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.common.result.safeApiCall
import com.garfiec.librechat.core.data.db.dao.MessageDao
import com.garfiec.librechat.core.data.mapper.toEntity
import com.garfiec.librechat.core.data.mapper.toModels
import com.garfiec.librechat.core.model.Message
import com.garfiec.librechat.core.model.request.BranchMessageRequest
import com.garfiec.librechat.core.model.request.UpdateMessageRequest
import com.garfiec.librechat.core.network.api.MessagesApi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

class MessageRepositoryImpl(
    private val messagesApi: MessagesApi,
    private val messageDao: MessageDao,
    private val activeAccountProvider: ActiveAccountProvider,
    private val dispatcher: CoroutineDispatcher,
) : MessageRepository {

    override fun observeMessages(conversationId: String): Flow<List<Message>> =
        activeAccountProvider.flatMapAccountOrEmpty(emptyList<Message>()) { account ->
            messageDao.observeMessagesForAccount(conversationId, account.value)
                .map { entities -> entities.toModels() }
        }.flowOn(dispatcher)

    override suspend fun getMessages(conversationId: String): Result<List<Message>> {
        val result = safeApiCall {
            // Capture identity before the network suspend so an in-flight account switch can't
            // mis-attribute these rows; skip caching when unresolved rather than stamping a null orphan.
            val accountId = activeAccountProvider.currentAccountId()?.value
            val messages = messagesApi.getMessages(conversationId)
            if (accountId != null) {
                messageDao.upsertAll(messages.entitiesFor(accountId))
            }
            messages
        }

        // On network failure, fall back to cached messages for the active account only.
        if (result is Result.Error) {
            val account = activeAccountProvider.currentAccountId()
            val cached = account
                ?.let { messageDao.observeMessagesForAccount(conversationId, it.value).first() }
                ?: emptyList()
            if (cached.isNotEmpty()) {
                Logger.d { "Using cached messages for $conversationId (network unavailable)" }
                return Result.Success(cached.toModels())
            }
        }
        return result
    }

    override suspend fun cacheMessages(messages: List<Message>) {
        if (messages.isEmpty()) return
        // Don't persist when no account is resolved (warming / logged out): a null-stamped row is
        // invisible to every account-filtered read and unreapable by the scoped logout purge.
        val accountId = activeAccountProvider.currentAccountId()?.value ?: return
        messageDao.upsertAll(messages.entitiesFor(accountId))
    }

    override suspend fun refreshMessages(conversationId: String): Result<List<Message>> {
        return safeApiCall {
            val accountId = activeAccountProvider.currentAccountId()?.value
            val messages = messagesApi.getMessages(conversationId)
            if (accountId != null) {
                // Full replace: delete stale cache then insert fresh server data
                messageDao.replaceAllForConversation(conversationId, messages.entitiesFor(accountId))
            }
            messages
        }
    }

    override suspend fun updateFeedback(
        conversationId: String,
        messageId: String,
        feedback: String?,
    ): Result<Unit> {
        return safeApiCall {
            messagesApi.updateFeedback(conversationId, messageId, feedback)
            // Update local cache
            messageDao.updateFeedback(messageId, feedback)
        }
    }

    override suspend fun updateMessageText(
        conversationId: String,
        messageId: String,
        text: String,
    ): Result<Unit> {
        return safeApiCall {
            messagesApi.updateMessage(conversationId, messageId, UpdateMessageRequest(text = text))
            messageDao.updateText(messageId, text)
        }
    }

    override suspend fun branchMessage(
        conversationId: String,
        messageId: String,
        agentId: String?,
    ): Result<Message> {
        return safeApiCall {
            messagesApi.branchMessage(
                BranchMessageRequest(
                    conversationId = conversationId,
                    messageId = messageId,
                    agentId = agentId,
                ),
            )
        }
    }

    // Maps server messages to entities stamped with the account captured at request time.
    private fun List<Message>.entitiesFor(accountId: String) =
        map { it.toEntity().copy(accountId = accountId) }
}
