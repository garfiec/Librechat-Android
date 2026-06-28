package com.garfiec.librechat.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlin.time.Clock

@Entity(tableName = "drafts")
data class DraftEntity(
    @PrimaryKey
    @ColumnInfo(name = "conversation_id")
    val conversationId: String,
    @ColumnInfo(name = "text")
    val text: String,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = Clock.System.now().toEpochMilliseconds(),
    /** Row-tenancy owner (self-owning; drafts can be conversation-less/new-chat). See ConversationEntity.accountId. */
    val accountId: String? = null,
)
