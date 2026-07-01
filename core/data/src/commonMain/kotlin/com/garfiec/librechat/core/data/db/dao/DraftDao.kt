package com.garfiec.librechat.core.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.garfiec.librechat.core.data.db.entity.DraftEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DraftDao {

    @Upsert
    suspend fun upsertDraft(draft: DraftEntity)

    // --- Account-scoped reads (row-tenancy): filter accountId on every read incl. by-PK. ---

    @Query("SELECT * FROM drafts WHERE conversation_id = :conversationId AND accountId = :accountId")
    suspend fun getDraftForAccount(conversationId: String, accountId: String): DraftEntity?

    @Query("SELECT * FROM drafts WHERE accountId = :accountId ORDER BY updated_at DESC")
    fun observeDraftsForAccount(accountId: String): Flow<List<DraftEntity>>

    // Logout / account-remove scoped purge (the leak fix): delete only this account's rows.
    @Query("DELETE FROM drafts WHERE accountId = :accountId")
    suspend fun deleteAllForAccount(accountId: String)

    // Single-row delete scoped to the active account. drafts share NEW_CHAT_DRAFT_KEY as one PK row
    // across accounts, so an unscoped delete-by-id would destroy another account's sentinel draft; the
    // accountId predicate makes a delete a no-op unless the row belongs to the caller.
    @Query("DELETE FROM drafts WHERE conversation_id = :conversationId AND accountId = :accountId")
    suspend fun deleteDraftForAccount(conversationId: String, accountId: String)
}
