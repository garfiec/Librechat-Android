package com.garfiec.librechat.feature.agents.components.model

import androidx.compose.runtime.Immutable
import com.garfiec.librechat.core.model.ToolFavoriteItemType

/**
 * What kind of thing a [MarketplaceItem] is.
 *
 * Mirrors upstream's catalog `kind`, and each value maps onto the tool-favorites `itemType` that
 * pins it — the two vocabularies are the same one, which is why favouriting works uniformly
 * across a list that mixes capabilities, plugin tools, MCP servers and skills.
 *
 * Upstream's fifth kind, `action`, is deliberately absent: actions are defined per agent rather
 * than picked from a shared catalog, they are not favouritable server-side, and mobile already
 * edits them in their own panel.
 */
enum class MarketplaceKind(val favoriteType: ToolFavoriteItemType) {
    /** A capability the server implements itself (code interpreter, file search, web search…). */
    BUILTIN(ToolFavoriteItemType.BUILTIN),

    /** A plugin tool from `GET /api/agents/tools`. */
    TOOL(ToolFavoriteItemType.TOOL),

    /** One MCP server. Selection is still per tool; the pin and the group are per server. */
    MCP(ToolFavoriteItemType.MCP),

    /** A skill from `GET /api/skills`. */
    SKILL(ToolFavoriteItemType.SKILL),
}

/**
 * One row in the agent editor's unified tool picker.
 *
 * The picker exists because the four kinds above were four separate pickers, each with its own
 * search box, while an agent's tools are one list to the person building it. Collapsing them
 * means the id space has to be explicit: [id] is the identifier the *agent record* stores
 * (a plugin key, an MCP tool name, a skill `_id`, a capability marker), while [favoriteId] is
 * what the favorites route pins, which for MCP is the server rather than the individual tool.
 */
@Immutable
data class MarketplaceItem(
    val kind: MarketplaceKind,
    val id: String,
    /**
     * Display name, or null for a built-in capability.
     *
     * Built-ins are the only rows this app names itself — everything else is labelled by the
     * server — so they are the only ones that must be translated, and their label is resolved
     * from [labelRes] at render time instead of being baked in here.
     */
    val name: String? = null,
    val description: String? = null,
    /** Resource pair naming a built-in capability. Null for server-provided rows. */
    val labelRes: MarketplaceBuiltinLabel? = null,
    val iconUrl: String? = null,
    /** MCP server this tool belongs to; null for every other kind. Also the group header. */
    val serverName: String? = null,
) {
    /** Stable list key — [id] alone collides across kinds (a skill and a tool may share a name). */
    val itemKey: String get() = "${kind.favoriteType.wireName}:$id"

    /**
     * The id the favorites route pins.
     *
     * For MCP this is the server, not the tool: upstream's catalog lists MCP *servers* as
     * favouritable entries, and pinning one tool out of a server the user pins as a whole would
     * make the star mean something different on that row than on every other one.
     */
    val favoriteId: String get() = if (kind == MarketplaceKind.MCP) serverName ?: id else id
}

/** Names a built-in capability. Only the enum travels through state; the strings stay in UI. */
enum class MarketplaceBuiltinLabel {
    CODE_INTERPRETER,
    FILE_SEARCH,
    WEB_SEARCH,
    FILE_CONTEXT,
}
