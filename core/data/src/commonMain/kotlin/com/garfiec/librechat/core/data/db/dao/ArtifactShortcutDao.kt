package com.garfiec.librechat.core.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.garfiec.librechat.core.data.db.entity.ArtifactShortcutEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ArtifactShortcutDao {

    @Upsert
    suspend fun upsert(shortcut: ArtifactShortcutEntity)

    @Query("SELECT * FROM artifact_shortcuts WHERE id = :id")
    suspend fun getById(id: String): ArtifactShortcutEntity?

    @Query("SELECT * FROM artifact_shortcuts ORDER BY created_at DESC")
    fun observeAll(): Flow<List<ArtifactShortcutEntity>>

    @Query("DELETE FROM artifact_shortcuts WHERE id = :id")
    suspend fun deleteById(id: String)
}
