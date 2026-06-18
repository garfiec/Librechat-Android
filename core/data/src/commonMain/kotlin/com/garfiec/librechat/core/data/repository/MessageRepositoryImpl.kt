package com.garfiec.librechat.core.data.repository

import co.touchlab.kermit.Logger
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
    private val dispatcher: CoroutineDispatcher,
) : MessageRepository {

    override fun observeMessages(conversationId: String): Flow<List<Message>> {
        return messageDao.getMessagesForConversation(conversationId)
            .map { entities -> entities.toModels() }
            .flowOn(dispatcher)
    }

    override suspend fun getMessages(conversationId: String): Result<List<Message>> {
        val result = safeApiCall {
            val messages = messagesApi.getMessages(conversationId)
            cacheMessages(messages)
            messages
        }

        // On network failure, fall back to cached messages
        if (result is Result.Error) {
            val cached = messageDao.getMessagesForConversation(conversationId).first()
            if (cached.isNotEmpty()) {
                Logger.d { "Using cached messages for $conversationId (network unavailable)" }
                return Result.Success(cached.toModels())
            }
        }
        return result
    }

    override suspend fun cacheMessages(messages: List<Message>) {
        if (messages.isEmpty()) return
        messageDao.upsertAll(messages.map { it.toEntity() })
    }

    override suspend fun refreshMessages(conversationId: String): Result<List<Message>> {
        return safeApiCall {
            val messages = messagesApi.getMessages(conversationId)
            val entities = messages.map { it.toEntity() }
            // Full replace: delete stale cache then insert fresh server data
            messageDao.replaceAllForConversation(conversationId, entities)
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
}
