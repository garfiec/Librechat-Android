package com.garfiec.librechat.core.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.garfiec.librechat.core.common.identity.CrossAccount
import com.garfiec.librechat.core.data.db.entity.ConversationTagEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationTagDao {

    // --- Account-scoped read (row-tenancy): a tag refresh for B must not surface A's tags. ---

    @Query("SELECT * FROM conversation_tags WHERE accountId = :accountId ORDER BY position ASC")
    fun observeTagsForAccount(accountId: String): Flow<List<ConversationTagEntity>>

    @Upsert
    suspend fun upsertAll(tags: List<ConversationTagEntity>)

    // --- Account-scoped writes (row-tenancy): a refresh/clear for B must not wipe A's tags. ---

    @Query("DELETE FROM conversation_tags WHERE accountId = :accountId")
    suspend fun deleteAllForAccount(accountId: String)

    // Scoped replace: the delete leg threads accountId, so a refresh for one account never wipes
    // another's tags. @CrossAccount suppresses the concrete-body lint; the body is account-safe.
    @CrossAccount
    @Transaction
    suspend fun replaceAllForAccount(accountId: String, tags: List<ConversationTagEntity>) {
        deleteAllForAccount(accountId)
        upsertAll(tags)
    }
}
