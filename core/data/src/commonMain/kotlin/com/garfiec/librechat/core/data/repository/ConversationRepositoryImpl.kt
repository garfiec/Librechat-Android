package com.garfiec.librechat.core.data.repository

import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.common.result.safeApiCall
import com.garfiec.librechat.core.data.db.dao.ConversationDao
import com.garfiec.librechat.core.data.mapper.toEntity
import com.garfiec.librechat.core.data.mapper.toModel
import com.garfiec.librechat.core.data.mapper.toModels
import com.garfiec.librechat.core.model.Conversation
import com.garfiec.librechat.core.model.SAVED_TAG
import com.garfiec.librechat.core.model.request.ForkConversationRequest
import com.garfiec.librechat.core.network.api.ConversationsApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import kotlin.time.Clock

class ConversationRepositoryImpl(
    private val conversationsApi: ConversationsApi,
    private val conversationDao: ConversationDao,
    private val json: Json,
) : ConversationRepository {

    override fun observeConversations(isArchived: Boolean): Flow<Result<List<Conversation>>> {
        return conversationDao.getAllConversations(
            isArchived = isArchived,
        ).map { entities ->
            Result.Success(entities.toModels()) as Result<List<Conversation>>
        }
    }

    override suspend fun loadNextPage(
        cursor: String?,
        tags: List<String>?,
        search: String?,
        sortBy: String?,
        sortDirection: String?,
        isArchived: Boolean,
    ): Result<String?> {
        return safeApiCall {
            val response = conversationsApi.getConversations(
                cursor = cursor,
                isArchived = isArchived,
                tags = tags,
                search = search,
                sortBy = sortBy,
                sortDirection = sortDirection,
            )
            conversationDao.upsertPreservingTags(response.conversations.map { it.toEntity() })
            response.nextCursor
        }
    }

    override suspend fun getConversation(id: String): Result<Conversation> {
        val cached = conversationDao.getById(id)
        if (cached != null) return Result.Success(cached.toModel())
        return refreshConversation(id)
    }

    override suspend fun refreshConversation(id: String): Result<Conversation> {
        return safeApiCall {
            val conversation = conversationsApi.getConversation(id)
            conversationDao.upsertPreservingTags(conversation.toEntity())
            conversation
        }
    }

    override suspend fun updateTitle(id: String, title: String): Result<Conversation> {
        return safeApiCall {
            val updated = conversationsApi.updateTitle(id, title)
            conversationDao.updateTitle(id, title, Clock.System.now().toEpochMilliseconds())
            updated
        }
    }

    override suspend fun generateTitle(conversationId: String): Result<String> {
        return safeApiCall {
            val response = conversationsApi.generateTitle(conversationId)
            conversationDao.updateTitle(conversationId, response.title, Clock.System.now().toEpochMilliseconds())
            response.title
        }
    }

    override suspend fun archive(id: String, isArchived: Boolean): Result<Conversation> {
        return safeApiCall {
            val updated = conversationsApi.archive(id, isArchived)
            conversationDao.updateArchived(id, isArchived, Clock.System.now().toEpochMilliseconds())
            updated
        }
    }

    override suspend fun delete(id: String): Result<Unit> {
        return safeApiCall {
            conversationsApi.deleteConversation(id)
            conversationDao.deleteById(id)
        }
    }

    override suspend fun forkConversation(
        conversationId: String,
        messageId: String,
        option: String?,
        splitAtTarget: Boolean?,
        latestMessageId: String?,
    ): Result<Conversation> {
        return safeApiCall {
            val response = conversationsApi.forkConversation(
                ForkConversationRequest(
                    conversationId = conversationId,
                    messageId = messageId,
                    option = option,
                    splitAtTarget = splitAtTarget,
                    latestMessageId = latestMessageId,
                ),
            )
            val conversation = response.conversation
            conversation.conversationId?.let { conversationDao.upsert(conversation.toEntity()) }
            conversation
        }
    }

    override suspend fun duplicateConversation(
        conversationId: String,
        title: String?,
    ): Result<Conversation> {
        return safeApiCall {
            val duplicated = conversationsApi.duplicateConversation(conversationId, title)
            duplicated.conversationId?.let { conversationDao.upsert(duplicated.toEntity()) }
            duplicated
        }
    }

    override suspend fun importConversation(jsonContent: String): Result<Conversation> {
        return safeApiCall {
            val fileBytes = jsonContent.encodeToByteArray()
            val imported = conversationsApi.importConversations(
                fileBytes = fileBytes,
                filename = "conversation.json",
                contentType = "application/json",
            )
            imported.conversationId?.let { conversationDao.upsert(imported.toEntity()) }
            imported
        }
    }

    override suspend fun deleteAll(): Result<Unit> {
        return safeApiCall {
            conversationsApi.deleteAllConversations()
            conversationDao.deleteAll()
        }
    }

    override suspend fun saveConversation(conversation: Conversation) {
        val id = conversation.conversationId ?: return
        if (id.isBlank()) return
        conversationDao.upsertPreservingTags(conversation.toEntity())
    }

    override suspend fun updateConversationTagsLocal(id: String, tags: List<String>) {
        conversationDao.updateTags(id, encodeTags(tags), Clock.System.now().toEpochMilliseconds())
    }

    // Reconciles SAVED_TAG attachment between the local Room cache and server by
    // paginating `GET /api/convos?tags=Saved`. Needed because upstream's
    // getConvosByCursor projection omits `tags`, so the main conversation list
    // endpoint can't deliver cross-client favorite changes. Only the reserved
    // SAVED_TAG is synced; other user-created tags aren't fetched here. Known
    // gap: the stale-removal pass below only scans non-archived rows, so a
    // conversation that was archived while favorited and later unfavorited
    // elsewhere will keep its local SAVED_TAG until the user unarchives it.
    override suspend fun syncFavoritesFromServer(): Result<Unit> = safeApiCall {
        val serverFavoriteIds = mutableSetOf<String>()
        var cursor: String? = null
        do {
            val response = conversationsApi.getConversations(
                cursor = cursor,
                tags = listOf(SAVED_TAG),
            )
            for (convo in response.conversations) {
                val id = convo.conversationId ?: continue
                serverFavoriteIds.add(id)
                val existing = conversationDao.getById(id)
                if (existing == null) {
                    conversationDao.upsert(
                        convo.toEntity().copy(tags = encodeTags(listOf(SAVED_TAG))),
                    )
                } else {
                    val currentTags = existing.toModel().tags
                    if (SAVED_TAG !in currentTags) {
                        updateConversationTagsLocal(id, currentTags + SAVED_TAG)
                    }
                }
            }
            cursor = response.nextCursor
        } while (cursor != null)

        val localEntities = conversationDao.getAllConversations(isArchived = false).first()
        for (entity in localEntities) {
            val currentTags = entity.toModel().tags
            if (SAVED_TAG in currentTags && entity.conversationId !in serverFavoriteIds) {
                updateConversationTagsLocal(
                    entity.conversationId,
                    currentTags.filterNot { it == SAVED_TAG },
                )
            }
        }
    }

    private fun encodeTags(tags: List<String>): String =
        json.encodeToString(ListSerializer(serializer<String>()), tags)
}
