package com.garfiec.librechat.core.model

import kotlinx.serialization.Serializable

/**
 * A user-pinned favorite from `GET/POST /api/user/settings/favorites`.
 *
 * Upstream contract (v0.8.5): each entry must have either [agentId], or the
 * combination of [model] + [endpoint], or a [spec]. Having multiple identifier
 * variants on one entry, or none, is rejected server-side with a 400.
 *
 * Server-enforced limits: 50 entries total, 256 characters per string field.
 * `MAX_FAVORITES` and `MAX_STRING_LENGTH` in [FavoritesLimits] mirror those
 * caps so the client can short-circuit out-of-bounds writes.
 *
 * [spec] is passed through unchanged; mobile does not yet render a spec picker,
 * but round-trip fidelity keeps other clients' pinned specs intact across saves.
 */
@Serializable
data class UserFavorite(
    val agentId: String? = null,
    val model: String? = null,
    val endpoint: String? = null,
    val spec: String? = null,
)

object FavoritesLimits {
    const val MAX_FAVORITES: Int = 50
    const val MAX_STRING_LENGTH: Int = 256
}
