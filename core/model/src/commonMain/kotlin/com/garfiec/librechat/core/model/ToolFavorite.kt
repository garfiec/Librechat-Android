package com.garfiec.librechat.core.model

import kotlinx.serialization.Serializable

/**
 * What a [ToolFavorite] points at.
 *
 * The compound `(itemType, itemId)` pair is the favorite's identity AND the path of the PUT /
 * DELETE that toggles it, so an item type this client cannot name is one it cannot address
 * either — [ToolFavoriteWire.toDomain] drops those rows instead of surfacing an unpinnable chip.
 */
enum class ToolFavoriteItemType(val wireName: String) {
    BUILTIN("builtin"),
    TOOL("tool"),
    MCP("mcp"),
    SKILL("skill"),
    ;

    companion object {
        fun fromWire(value: String?): ToolFavoriteItemType? =
            entries.firstOrNull { it.wireName == value }
    }
}

/**
 * A marketplace item the user pinned, from `GET /api/user/settings/favorites/tools`
 * (upstream v0.8.8 line, #13952).
 *
 * Separate collection and separate route from [UserFavorite], which pins whole *conversation
 * starting points* (an agent, a model+endpoint pair, a spec) and is written as one whole-list
 * overwrite. This one pins the *ingredients* an agent is built from and is written per item:
 * `PUT` / `DELETE /favorites/tools/:itemType/:itemId`. Neither supersedes the other.
 *
 * Skill favorites live here as [ToolFavoriteItemType.SKILL] — the older `/favorites` route never
 * grew a skill identifier and 400s on one.
 */
data class ToolFavorite(
    val itemType: ToolFavoriteItemType,
    val itemId: String,
) {
    /** `itemType:itemId`, upstream's `itemKey` format — usable directly as a set key. */
    val itemKey: String get() = "${itemType.wireName}:$itemId"
}

/**
 * Wire shape of a tool favorite, decoded leniently.
 *
 * [ToolFavoriteItemType] is a closed set today and upstream is free to extend it (the reserved
 * `TUserFavorite.skillId` becoming `itemType:'skill'` is precisely that having already happened
 * once). A non-null enum field here would make one unrecognized row throw and take the whole
 * list with it, so the type stays a string until [toDomain] narrows it.
 */
@Serializable
data class ToolFavoriteWire(
    val itemType: String? = null,
    val itemId: String? = null,
) {
    fun toDomain(): ToolFavorite? {
        val type = ToolFavoriteItemType.fromWire(itemType) ?: return null
        val id = itemId?.takeIf { it.isNotBlank() } ?: return null
        return ToolFavorite(type, id)
    }
}

object ToolFavoritesLimits {
    /** Server-side cap per user (`MAX_TOOL_FAVORITES`); a write past it 400s. */
    const val MAX_TOOL_FAVORITES: Int = 100

    /** Longest `itemId` the route accepts before returning `INVALID_ITEM_ID`. */
    const val MAX_ITEM_ID_LENGTH: Int = 256
}
