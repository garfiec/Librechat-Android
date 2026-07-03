package com.garfiec.librechat.core.data.repository

import co.touchlab.kermit.Logger
import com.garfiec.librechat.core.common.identity.AccountId
import com.garfiec.librechat.core.common.identity.ActiveAccountProvider
import com.garfiec.librechat.core.common.identity.currentAccountId
import com.garfiec.librechat.core.common.identity.flatMapAccountOrEmpty
import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.common.result.safeApiCall
import com.garfiec.librechat.core.data.datastore.AccountRoster
import com.garfiec.librechat.core.data.db.dao.ConversationDao
import com.garfiec.librechat.core.data.mapper.toEntity
import com.garfiec.librechat.core.data.mapper.toModel
import com.garfiec.librechat.core.data.mapper.toModels
import com.garfiec.librechat.core.model.Conversation
import com.garfiec.librechat.core.model.ConversationPage
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
    private val activeAccountProvider: ActiveAccountProvider,
    private val roster: AccountRoster,
    private val json: Json,
) : ConversationRepository {

    override fun observeConversations(isArchived: Boolean): Flow<Result<List<Conversation>>> =
        activeAccountProvider.flatMapAccountOrEmpty(Result.Success(emptyList())) { account ->
            conversationDao.observeConversationsForAccount(account.value, isArchived)
                .map { entities -> Result.Success(entities.toModels()) as Result<List<Conversation>> }
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
            // Capture identity before the network suspend so an in-flight account switch can't
            // mis-attribute these rows; skip the cache write entirely when unresolved (warming /
            // logged out) rather than stamping a null orphan.
            val accountId = activeAccountProvider.currentAccountId()?.value
            val response = conversationsApi.getConversations(
                cursor = cursor,
                isArchived = isArchived,
                tags = tags,
                search = search,
                sortBy = sortBy,
                sortDirection = sortDirection,
            )
            if (accountId != null) {
                conversationDao.upsertPreservingTags(
                    accountId,
                    response.conversations.map { it.toEntity().copy(accountId = accountId) },
                )
            }
            response.nextCursor
        }
    }

    override suspend fun getConversationsForProject(
        projectId: String,
        cursor: String?,
        limit: Int,
        sortBy: String?,
        sortDirection: String?,
    ): Result<ConversationPage> {
        return safeApiCall {
            // Capture identity before the network suspend so an in-flight account switch can't
            // mis-attribute these rows; skip the cache write entirely when unresolved rather than
            // stamping a null orphan the account-filtered reads can't see and the reconciler reaps.
            val accountId = activeAccountProvider.currentAccountId()?.value
            val response = conversationsApi.getConversations(
                cursor = cursor,
                limit = limit,
                projectId = projectId,
                sortBy = sortBy,
                sortDirection = sortDirection,
            )
            // Warm the cache (these are real conversations, and the upsert keeps each row's
            // chatProjectId fresh for the drawer move-picker), but return the page directly:
            // the project-filtered LIST view is network-direct by design, not a Room query.
            if (accountId != null) {
                conversationDao.upsertPreservingTags(
                    accountId,
                    response.conversations.map { it.toEntity().copy(accountId = accountId) },
                )
            }
            ConversationPage(
                conversations = response.conversations,
                nextCursor = response.nextCursor,
            )
        }
    }

    override suspend fun getConversation(id: String, originAccount: AccountId?): Result<Conversation> {
        val account = resolveWriteAccountId(originAccount, activeAccountProvider, roster)
        val cached = account?.let { conversationDao.getByIdForAccount(id, it) }
        if (cached != null) return Result.Success(cached.toModel())
        return refreshConversation(id, originAccount)
    }

    override suspend fun refreshConversation(id: String, originAccount: AccountId?): Result<Conversation> {
        // Origin-capture: this finalize may land after a switch. When the origin account is no longer
        // live, skip the network refresh (the GET rides the LIVE snapshot and would carry this id to
        // the new account's server) and serve the cached copy — a routine post-switch skip, not a bug.
        if (!originTransportAllowed(originAccount, activeAccountProvider)) {
            val account = resolveWriteAccountId(originAccount, activeAccountProvider, roster)
            val cached = account?.let { conversationDao.getByIdForAccount(id, it)?.toModel() }
            return cached?.let { Result.Success(it) }
                ?: Result.Error(IllegalStateException("Skipped refresh for a non-active account's conversation"))
        }
        return safeApiCall {
            val accountId = resolveWriteAccountId(originAccount, activeAccountProvider, roster)
            val conversation = conversationsApi.getConversation(id)
            if (accountId != null) {
                conversationDao.upsertPreservingTags(accountId, conversation.toEntity().copy(accountId = accountId))
            }
            conversation
        }
    }

    override suspend fun updateTitle(id: String, title: String): Result<Conversation> {
        return safeApiCall {
            // Capture identity before the network suspend; skip the local cache update when unresolved
            // rather than running an account-blind by-PK write.
            val accountId = activeAccountProvider.currentAccountId()?.value
            val updated = conversationsApi.updateTitle(id, title)
            if (accountId != null) {
                conversationDao.updateTitle(id, title, Clock.System.now().toEpochMilliseconds(), accountId)
            }
            updated
        }
    }

    override suspend fun generateTitle(conversationId: String, originAccount: AccountId?): Result<String> {
        // Origin-capture: the gen_title long-poll returns minutes later, possibly post-switch — stamp
        // the account that started the send, not the live active one. The POST rides the LIVE snapshot,
        // so it must not fire once the origin is no longer active (it would carry this conversation id
        // to the new account's server under its bearer). A benign skip, not a thrown invariant.
        if (!originTransportAllowed(originAccount, activeAccountProvider)) {
            return Result.Error(
                IllegalStateException("Skipped title generation for a non-active account's conversation"),
            )
        }
        return safeApiCall {
            val accountId = resolveWriteAccountId(originAccount, activeAccountProvider, roster)
            val response = conversationsApi.generateTitle(conversationId)
            if (accountId != null) {
                conversationDao.updateTitle(conversationId, response.title, Clock.System.now().toEpochMilliseconds(), accountId)
            }
            response.title
        }
    }

    override suspend fun archive(id: String, isArchived: Boolean): Result<Conversation> {
        return safeApiCall {
            val accountId = activeAccountProvider.currentAccountId()?.value
            val updated = conversationsApi.archive(id, isArchived)
            if (accountId != null) {
                conversationDao.updateArchived(id, isArchived, Clock.System.now().toEpochMilliseconds(), accountId)
            }
            updated
        }
    }

    override suspend fun pin(id: String, pinned: Boolean): Result<Conversation> {
        return safeApiCall {
            // Capture identity before the network suspend; skip the local cache update when unresolved
            // rather than running an account-blind by-PK write.
            val accountId = activeAccountProvider.currentAccountId()?.value
            val updated = conversationsApi.pin(id, pinned)
            if (accountId != null) {
                conversationDao.updatePinned(id, pinned, accountId)
            }
            updated
        }
    }

    override suspend fun delete(id: String): Result<Unit> {
        return safeApiCall {
            val accountId = activeAccountProvider.currentAccountId()?.value
            conversationsApi.deleteConversation(id)
            // The local delete must be account-scoped, so it can't run while unresolved. The server row
            // is already gone, so the stale local row lingers in the list until a resolved-account wipe;
            // log it. In practice a delete is a foreground action where the account is resolved.
            if (accountId != null) {
                conversationDao.deleteById(id, accountId)
            } else {
                Logger.w { "Server-deleted $id but kept local row: no resolved account to scope the delete" }
            }
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
            val accountId = activeAccountProvider.currentAccountId()?.value
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
            cacheOwned(accountId, conversation)
            conversation
        }
    }

    override suspend fun duplicateConversation(
        conversationId: String,
        title: String?,
    ): Result<Conversation> {
        return safeApiCall {
            val accountId = activeAccountProvider.currentAccountId()?.value
            val duplicated = conversationsApi.duplicateConversation(conversationId, title)
            cacheOwned(accountId, duplicated)
            duplicated
        }
    }

    override suspend fun importConversation(jsonContent: String): Result<Conversation> {
        return safeApiCall {
            val accountId = activeAccountProvider.currentAccountId()?.value
            val fileBytes = jsonContent.encodeToByteArray()
            val imported = conversationsApi.importConversations(
                fileBytes = fileBytes,
                filename = "conversation.json",
                contentType = "application/json",
            )
            cacheOwned(accountId, imported)
            imported
        }
    }

    override suspend fun deleteAll(): Result<Unit> {
        // Scope the local wipe to the active account: an unscoped DELETE would also destroy another
        // account's cached conversations if their rows coexist (e.g. a prior user who never logged out).
        val accountId = activeAccountProvider.currentAccountId()?.value
        return safeApiCall {
            conversationsApi.deleteAllConversations()
            if (accountId != null) conversationDao.deleteAllForAccount(accountId)
        }
    }

    override suspend fun saveConversation(conversation: Conversation, originAccount: AccountId?) {
        val id = conversation.conversationId ?: return
        if (id.isBlank()) return
        // Origin-capture: the final-event save lands after the stream, possibly post-switch — stamp
        // the originating account, and skip when unresolved or removed-since-capture.
        val accountId = resolveWriteAccountId(originAccount, activeAccountProvider, roster) ?: return
        conversationDao.upsertPreservingTags(accountId, conversation.toEntity().copy(accountId = accountId))
    }

    override suspend fun updateConversationTagsLocal(id: String, tags: List<String>) {
        // Local-only tag write; skip when unresolved rather than running an account-blind by-PK update.
        // Unlike the server-backed writes there is no remote copy to recover from, so a drop loses the
        // edit outright — log it. In practice the account is resolved during active use; an unresolved
        // window here only happens mid warming / soft-expiry re-auth.
        val accountId = activeAccountProvider.currentAccountId()?.value ?: run {
            Logger.w { "Dropping local tag update for $id: no resolved account" }
            return
        }
        conversationDao.updateTags(id, encodeTags(tags), Clock.System.now().toEpochMilliseconds(), accountId)
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
        val account = activeAccountProvider.currentAccountId() ?: return@safeApiCall
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
                val existing = conversationDao.getByIdForAccount(id, account.value)
                if (existing == null) {
                    conversationDao.upsert(
                        convo.toEntity()
                            .copy(tags = encodeTags(listOf(SAVED_TAG)), accountId = account.value),
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

        val localEntities = conversationDao.observeConversationsForAccount(account.value, isArchived = false).first()
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

    // Stamps + caches a single server-returned conversation for the account captured at request time.
    // Skips when no account is resolved (warming / logged out → never write a null orphan) or the
    // server returned no conversationId.
    private suspend fun cacheOwned(accountId: String?, conversation: Conversation) {
        if (accountId != null && conversation.conversationId != null) {
            conversationDao.upsert(conversation.toEntity().copy(accountId = accountId))
        }
    }

    private fun encodeTags(tags: List<String>): String =
        json.encodeToString(ListSerializer(serializer<String>()), tags)
}
