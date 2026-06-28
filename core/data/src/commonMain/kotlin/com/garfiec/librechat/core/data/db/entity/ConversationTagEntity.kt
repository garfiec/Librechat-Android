package com.garfiec.librechat.core.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "conversation_tags",
    indices = [Index(value = ["tag", "user"], unique = true)],
)
data class ConversationTagEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val tag: String,
    val user: String,
    val description: String?,
    val count: Int = 0,
    val position: Int = 0,
    val createdAt: Long,
    val updatedAt: Long,
    /** Row-tenancy owner. See ConversationEntity.accountId. */
    val accountId: String? = null,
)
