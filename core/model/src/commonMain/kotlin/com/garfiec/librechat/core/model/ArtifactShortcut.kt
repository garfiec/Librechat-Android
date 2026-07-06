package com.garfiec.librechat.core.model

import kotlinx.serialization.Serializable

/**
 * A snapshot of a generated artifact that the user pinned to the device home screen (Android).
 *
 * Self-contained by design: the artifact's content is copied in full at pin time, so the shortcut
 * survives the source conversation being edited or deleted and opens while logged out. Device-scoped,
 * not account-scoped — a launcher icon must outlive logout/account-switch, so there is no owning
 * account here (see [com.garfiec.librechat.core.model] siblings, which are account-scoped).
 *
 * [id] is the snapshot's stable identifier: the primary key, the pinned shortcut id, and the
 * `librechat://artifact/{id}` deep-link path segment.
 */
@Serializable
data class ArtifactShortcut(
    val id: String,
    val label: String,
    val emoji: String?,
    val identifier: String,
    val type: String,
    val title: String,
    val language: String?,
    val content: String,
    val version: Int,
    val createdAt: Long,
)
