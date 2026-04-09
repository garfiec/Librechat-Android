package com.garfiec.librechat.core.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.garfiec.librechat.core.data.db.entity.FileEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FileDao {
    @Query("SELECT * FROM files WHERE user = :userId ORDER BY createdAt DESC")
    fun getFilesForUser(userId: String): Flow<List<FileEntity>>

    @Query("SELECT * FROM files WHERE fileId = :fileId")
    suspend fun getById(fileId: String): FileEntity?

    @Upsert
    suspend fun upsert(file: FileEntity)

    @Upsert
    suspend fun upsertAll(files: List<FileEntity>)

    @Query("DELETE FROM files WHERE fileId = :fileId")
    suspend fun deleteById(fileId: String)

    @Query("DELETE FROM files")
    suspend fun deleteAll()
}
