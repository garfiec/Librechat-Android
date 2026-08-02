package com.garfiec.librechat.core.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.garfiec.librechat.core.data.db.entity.ServerEntity

/**
 * Reads and writes per-server gateway headers.
 *
 * Deliberately no observation method: `ServerRepositoryImpl` is the table's only writer and patches
 * its own writes, and a Room `Flow`'s query snapshot can predate a save it arrives after — reverting
 * a credential the user just entered.
 */
@Dao
interface ServerDao {

    @Query("SELECT * FROM servers")
    suspend fun getAll(): List<ServerEntity>

    /**
     * Safe as a whole-row write only because this table has exactly one non-key column and one
     * writer. Any future per-server column needs column-scoped writes instead.
     */
    @Upsert
    suspend fun upsert(server: ServerEntity)

    @Query("DELETE FROM servers WHERE server_id = :serverId")
    suspend fun deleteById(serverId: String)
}
