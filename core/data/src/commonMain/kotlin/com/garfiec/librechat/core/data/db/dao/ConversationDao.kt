package com.garfiec.librechat.core.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.garfiec.librechat.core.data.db.entity.ConversationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations WHERE user = :userId AND isArchived = :isArchived ORDER BY updatedAt DESC LIMIT :limit")
    fun getConversations(userId: String, isArchived: Boolean = false, limit: Int = 25): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE isArchived = :isArchived ORDER BY updatedAt DESC")
    fun getAllConversations(isArchived: Boolean = false): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE conversationId = :id")
    suspend fun getById(id: String): ConversationEntity?

    @Query("SELECT * FROM conversations WHERE conversationId = :id")
    fun observeById(id: String): Flow<ConversationEntity?>

    // --- Account-scoped reads (row-tenancy). Every read filters accountId, incl. by-PK:
    // a known/stale/deep-linked id from the shared DB must not let account B read A's row. These
    // are the forms the AccountScopedDb facade calls; the unfiltered forms above are removed once
    // repos migrate + the Detekt rule turns on. ---

    @Query("SELECT * FROM conversations WHERE accountId = :accountId AND isArchived = :isArchived ORDER BY updatedAt DESC")
    fun observeConversationsForAccount(accountId: String, isArchived: Boolean): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE conversationId = :id AND accountId = :accountId")
    suspend fun getByIdForAccount(id: String, accountId: String): ConversationEntity?

    @Query("SELECT * FROM conversations WHERE conversationId = :id AND accountId = :accountId")
    fun observeByIdForAccount(id: String, accountId: String): Flow<ConversationEntity?>

    @Upsert
    suspend fun upsert(conversation: ConversationEntity)

    @Upsert
    suspend fun upsertAll(conversations: List<ConversationEntity>)

    // Atomic read-merge-write that preserves locally-stored tags when a row
    // already exists. Used by paths that receive server responses without the
    // `tags` field (list endpoint + streaming save). Must run inside a single
    // transaction so it can't be interleaved by a concurrent updateTags write
    // from `syncFavoritesFromServer` (which would otherwise get clobbered).
    @Transaction
    suspend fun upsertPreservingTags(entities: List<ConversationEntity>) {
        for (entity in entities) {
            if (entity.conversationId.isBlank()) {
                upsert(entity)
                continue
            }
            val existing = getById(entity.conversationId)
            val toUpsert = if (existing != null) entity.copy(tags = existing.tags) else entity
            upsert(toUpsert)
        }
    }

    suspend fun upsertPreservingTags(entity: ConversationEntity) {
        upsertPreservingTags(listOf(entity))
    }

    @Query("DELETE FROM conversations WHERE conversationId = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM conversations WHERE user = :userId")
    suspend fun deleteAllForUser(userId: String)

    @Query("UPDATE conversations SET title = :title, updatedAt = :updatedAt WHERE conversationId = :id")
    suspend fun updateTitle(id: String, title: String, updatedAt: Long)

    @Query("UPDATE conversations SET isArchived = :isArchived, updatedAt = :updatedAt WHERE conversationId = :id")
    suspend fun updateArchived(id: String, isArchived: Boolean, updatedAt: Long)

    @Query("UPDATE conversations SET tags = :tagsJson, updatedAt = :updatedAt WHERE conversationId = :id")
    suspend fun updateTags(id: String, tagsJson: String, updatedAt: Long)

    @Query("DELETE FROM conversations")
    suspend fun deleteAll()

    // Logout / account-remove scoped purge (the leak fix): delete only this account's rows.
    @Query("DELETE FROM conversations WHERE accountId = :accountId")
    suspend fun deleteAllForAccount(accountId: String)
}
