package com.garfiec.librechat.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A pinned-artifact snapshot. Deliberately has NO accountId column: a home-screen launcher icon must
 * keep opening after logout / account-switch, so this table is device-scoped and is intentionally
 * excluded from AccountDataPurger's per-account teardown.
 */
@Entity(tableName = "artifact_shortcuts")
data class ArtifactShortcutEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "shortcut_label")
    val shortcutLabel: String,
    @ColumnInfo(name = "emoji")
    val emoji: String?,
    @ColumnInfo(name = "identifier")
    val identifier: String,
    @ColumnInfo(name = "type")
    val type: String,
    @ColumnInfo(name = "title")
    val title: String,
    @ColumnInfo(name = "language")
    val language: String?,
    @ColumnInfo(name = "content")
    val content: String,
    @ColumnInfo(name = "version")
    val version: Int,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
)
