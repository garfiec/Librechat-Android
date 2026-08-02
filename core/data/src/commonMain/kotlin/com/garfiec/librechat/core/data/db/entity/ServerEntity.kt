package com.garfiec.librechat.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One server's gateway headers (issue #287), keyed by the same `serverId` every other server-scoped
 * subsystem derives (`sha256(normalizeServerUrl(url))[:16]`). See `core/data/CLAUDE.md`.
 *
 * **Device-scoped, no accountId**, for the same reason [ArtifactShortcutEntity] is: this credential
 * is what lets you log back *in* after logging out, so it is deliberately absent from
 * `AccountDataPurger`'s per-account teardown and from the detekt tenancy rule's table list.
 *
 * **The primary key is the natural `serverId`**, not a surrogate — Room resolves `@Upsert` by primary
 * key, so a surrogate would make every save after the first match zero rows and vanish silently.
 *
 * **Adding any second column means making `ServerDao`'s whole-row `@Upsert` column-scoped first** — a
 * writer that doesn't know about the headers would otherwise blank them.
 */
@Entity(tableName = "servers")
data class ServerEntity(
    @PrimaryKey
    @ColumnInfo(name = "server_id")
    val serverId: String,
    /** The gateway headers as a JSON object. A server with none configured has no row at all. */
    @ColumnInfo(name = "custom_headers")
    val customHeaders: String,
)
