package com.garfiec.librechat.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "conversations",
    indices = [
        Index(value = ["isArchived", "updatedAt"]),
    ],
)
data class ConversationEntity(
    @PrimaryKey
    val conversationId: String,
    val title: String,
    val user: String,
    val endpoint: String?,
    val endpointType: String?,
    val model: String?,
    val agentId: String?,
    val isArchived: Boolean = false,
    @ColumnInfo(defaultValue = "0")
    val pinned: Boolean = false,
    /** Folder/project assignment (v0.8.7). Nullable: most conversations belong to no project. */
    val chatProjectId: String? = null,
    val tags: String,
    val iconURL: String?,
    val greeting: String?,
    val modelParams: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val lastSyncedAt: Long = 0,
    /**
     * Row-tenancy owner (account-isolation). Nullable: rows written
     * before the 4→5 migration are NULL until the one-time claim stamps them for the resolved
     * account. Reads filter on this; writes stamp it from the active session's immutable id.
     */
    val accountId: String? = null,
)
