package com.garfiec.librechat.core.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.garfiec.librechat.core.data.db.entity.PresetEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PresetDao {
    @Query("SELECT * FROM presets ORDER BY `order` ASC, title ASC")
    fun getAll(): Flow<List<PresetEntity>>

    @Query("SELECT * FROM presets WHERE presetId = :presetId")
    suspend fun getById(presetId: String): PresetEntity?

    @Upsert
    suspend fun upsert(preset: PresetEntity)

    @Query("DELETE FROM presets WHERE presetId = :presetId")
    suspend fun deleteById(presetId: String)

    @Query("DELETE FROM presets")
    suspend fun deleteAll()
}
