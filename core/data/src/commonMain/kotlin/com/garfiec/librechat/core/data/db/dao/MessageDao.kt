package com.garfiec.librechat.core.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.garfiec.librechat.core.data.db.entity.MessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt ASC")
    fun getMessagesForConversation(conversationId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE messageId = :messageId")
    suspend fun getById(messageId: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId AND parentMessageId = :parentMessageId ORDER BY createdAt ASC")
    suspend fun getSiblings(conversationId: String, parentMessageId: String): List<MessageEntity>

    // --- Account-scoped reads (row-tenancy): filter accountId on every read incl. by-PK. ---

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId AND accountId = :accountId ORDER BY createdAt ASC")
    fun observeMessagesForAccount(conversationId: String, accountId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE messageId = :messageId AND accountId = :accountId")
    suspend fun getByIdForAccount(messageId: String, accountId: String): MessageEntity?

    @Query(
        "SELECT * FROM messages WHERE conversationId = :conversationId AND parentMessageId = :parentMessageId " +
            "AND accountId = :accountId ORDER BY createdAt ASC",
    )
    suspend fun getSiblingsForAccount(
        conversationId: String,
        parentMessageId: String,
        accountId: String,
    ): List<MessageEntity>

    @Upsert
    suspend fun upsert(message: MessageEntity)

    @Upsert
    suspend fun upsertAll(messages: List<MessageEntity>)

    @Query("DELETE FROM messages WHERE messageId = :messageId")
    suspend fun deleteById(messageId: String)

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun deleteAllForConversation(conversationId: String)

    @Transaction
    suspend fun replaceAllForConversation(conversationId: String, messages: List<MessageEntity>) {
        deleteAllForConversation(conversationId)
        upsertAll(messages)
    }

    @Query("UPDATE messages SET feedback = :feedback WHERE messageId = :messageId")
    suspend fun updateFeedback(messageId: String, feedback: String?)

    @Query("UPDATE messages SET text = :text, content = NULL WHERE messageId = :messageId")
    suspend fun updateText(messageId: String, text: String)

    // Logout / account-remove scoped purge (the leak fix): delete only this account's rows.
    @Query("DELETE FROM messages WHERE accountId = :accountId")
    suspend fun deleteAllForAccount(accountId: String)
}
