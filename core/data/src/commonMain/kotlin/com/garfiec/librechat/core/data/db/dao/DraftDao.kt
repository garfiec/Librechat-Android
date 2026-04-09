package com.garfiec.librechat.core.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.garfiec.librechat.core.data.db.entity.DraftEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DraftDao {
    @Query("SELECT * FROM drafts WHERE conversation_id = :conversationId")
    suspend fun getDraft(conversationId: String): DraftEntity?

    @Upsert
    suspend fun upsertDraft(draft: DraftEntity)

    @Query("DELETE FROM drafts WHERE conversation_id = :conversationId")
    suspend fun deleteDraft(conversationId: String)

    @Query("SELECT * FROM drafts ORDER BY updated_at DESC")
    fun observeAllDrafts(): Flow<List<DraftEntity>>

    @Query("DELETE FROM drafts")
    suspend fun deleteAll()
}
