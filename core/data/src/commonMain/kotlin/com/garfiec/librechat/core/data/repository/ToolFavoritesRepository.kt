package com.garfiec.librechat.core.data.repository

import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.model.ToolFavorite
import com.garfiec.librechat.core.model.ToolFavoriteItemType
import kotlinx.coroutines.flow.StateFlow

/**
 * The user's pinned marketplace items — built-in capabilities, plugin tools, MCP servers and
 * skills — from the v0.8.8 `/api/user/settings/favorites/tools` routes.
 *
 * Deliberately a sibling of [FavoritesRepository] rather than an extension of it: that one owns
 * a whole-list overwrite of conversation starting points, this one owns per-item toggles of
 * agent ingredients. Merging them would mean every tool pin rewrote the user's pinned agents.
 */
interface ToolFavoritesRepository {
    /** Latest known pins. Empty until [refresh] runs — and on servers without the routes. */
    val favorites: StateFlow<Set<ToolFavorite>>

    /**
     * True once a [refresh] has succeeded against a server that has the routes.
     *
     * Callers use this to decide whether to *offer* pinning at all: a pre-0.8.8 server 404s
     * every write, and a star that always fails is worse than no star.
     */
    val isSupported: StateFlow<Boolean>

    /** Fetches the canonical set. A 404 (pre-0.8.8 server) resolves to unsupported, not an error. */
    suspend fun refresh(): Result<Set<ToolFavorite>>

    /** Pins or unpins one item, publishing optimistically and rolling back if the write fails. */
    suspend fun toggle(itemType: ToolFavoriteItemType, itemId: String): Result<Boolean>

    /** Drops the cache on logout / account switch so the next account starts clean. */
    fun clear()
}
