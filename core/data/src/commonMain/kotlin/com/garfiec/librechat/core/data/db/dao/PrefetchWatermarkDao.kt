package com.garfiec.librechat.core.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.garfiec.librechat.core.data.db.entity.PrefetchWatermarkEntity

/**
 * Every query is account-scoped. `prefetch_watermarks` is not one of the tables the tenancy detekt
 * rule infers, so nothing would fail the build if it weren't — which is exactly why it is written
 * that way by hand rather than left to the rule.
 */
@Dao
interface PrefetchWatermarkDao {

    @Query("SELECT * FROM prefetch_watermarks WHERE accountId = :accountId")
    suspend fun allForAccount(accountId: String): List<PrefetchWatermarkEntity>

    @Upsert
    suspend fun upsert(watermark: PrefetchWatermarkEntity)

    /**
     * Drops watermarks alongside the message rows they describe. Deleting messages without this
     * leaves the conversation looking permanently warm, so it is never re-fetched and never
     * repopulates — call the two together.
     */
    @Query("DELETE FROM prefetch_watermarks WHERE accountId = :accountId AND conversationId IN (:conversationIds)")
    suspend fun deleteFor(accountId: String, conversationIds: List<String>)

    @Query("DELETE FROM prefetch_watermarks WHERE accountId = :accountId")
    suspend fun deleteAllForAccount(accountId: String)
}
