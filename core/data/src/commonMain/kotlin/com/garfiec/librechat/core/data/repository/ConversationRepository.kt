package com.garfiec.librechat.core.data.repository

import com.garfiec.librechat.core.common.identity.AccountId
import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.model.Conversation
import com.garfiec.librechat.core.model.ConversationPage
import kotlinx.coroutines.flow.Flow

interface ConversationRepository {
    fun observeConversations(isArchived: Boolean = false): Flow<Result<List<Conversation>>>
    suspend fun loadNextPage(
        cursor: String?,
        tags: List<String>? = null,
        search: String? = null,
        sortBy: String? = null,
        sortDirection: String? = null,
        isArchived: Boolean = false,
    ): Result<String?>

    /**
     * Cache-first read of [id]. [originAccount] (origin-capture provenance) scopes both the cache
     * read and the fallback [refreshConversation] write to the account that started the operation, so
     * a read-through landing after a switch doesn't miss-then-refetch under the new account. Null
     * (foreground callers) uses the live active account.
     *
     * Deliberately no default on [originAccount] (here and on the other provenance-carrying methods):
     * every caller must decide between a send-time capture (any write that can land after a switch —
     * streamed finalize, debounced or queued work) and an explicit null (foreground, where entry *is*
     * land time). A silent default would let new deferred-write paths compile with land-time
     * attribution — the mis-attribution bug this parameter exists to prevent.
     */
    suspend fun getConversation(id: String, originAccount: AccountId?): Result<Conversation>

    /**
     * Fetches a cursor page of conversations filtered by [projectId] (a project id or
     * [com.garfiec.librechat.core.model.ChatProject.UNASSIGNED]). Returned directly to the
     * caller: the project-filtered list view is network-direct by design (not yet a Room-filtered
     * query). The page is still upserted into the cache for warmth, which also keeps each row's
     * chatProjectId current. Used by the project folder/browse views.
     */
    suspend fun getConversationsForProject(
        projectId: String,
        cursor: String? = null,
        limit: Int = 25,
        sortBy: String? = null,
        sortDirection: String? = null,
    ): Result<ConversationPage>

    /**
     * Fetches [id] from the server and upserts it into the cache, bypassing
     * [getConversation]'s cache-first read. Use when the local row is known to be
     * stale (e.g. picking up a server-generated title).
     */
    suspend fun refreshConversation(id: String, originAccount: AccountId?): Result<Conversation>
    suspend fun updateTitle(id: String, title: String): Result<Conversation>
    suspend fun generateTitle(conversationId: String, originAccount: AccountId?): Result<String>
    suspend fun archive(id: String, isArchived: Boolean): Result<Conversation>
    suspend fun pin(id: String, pinned: Boolean): Result<Conversation>
    suspend fun delete(id: String): Result<Unit>
    suspend fun forkConversation(
        conversationId: String,
        messageId: String,
        option: String? = null,
        splitAtTarget: Boolean? = null,
        latestMessageId: String? = null,
    ): Result<Conversation>
    suspend fun duplicateConversation(conversationId: String, title: String?): Result<Conversation>
    suspend fun importConversation(jsonContent: String): Result<Conversation>
    suspend fun deleteAll(): Result<Unit>
    suspend fun saveConversation(conversation: Conversation, originAccount: AccountId?)
    suspend fun updateConversationTagsLocal(id: String, tags: List<String>)
    suspend fun syncFavoritesFromServer(): Result<Unit>
}
