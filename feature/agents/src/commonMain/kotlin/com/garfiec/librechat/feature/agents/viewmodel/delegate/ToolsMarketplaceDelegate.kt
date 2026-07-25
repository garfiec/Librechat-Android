package com.garfiec.librechat.feature.agents.viewmodel.delegate

import co.touchlab.kermit.Logger
import com.garfiec.librechat.core.common.ToolConstants
import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.data.repository.ToolFavoritesRepository
import com.garfiec.librechat.feature.agents.components.model.MarketplaceBuiltinLabel
import com.garfiec.librechat.feature.agents.components.model.MarketplaceItem
import com.garfiec.librechat.feature.agents.components.model.MarketplaceKind
import com.garfiec.librechat.feature.agents.viewmodel.AgentEditorStateHandle
import com.garfiec.librechat.feature.agents.viewmodel.AgentEditorUiState
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Owns the agent editor's unified tool picker: the one catalog that lists built-in capabilities,
 * plugin tools, MCP servers and skills together, and the favorites that pin entries in it.
 *
 * Upstream #13952 collapsed three separate dialogs into one because the split was an artifact of
 * how the features shipped, not of how anyone builds an agent — you decide what the agent can
 * *do*, and whether that turns out to be a capability, a plugin or an MCP server is an
 * implementation detail. The catalog is derived from reference data the editor already loads, so
 * nothing here fetches tools; only the favorites are its own.
 *
 * Toggling an item routes back into the delegates that already own each kind's selection, so the
 * dedicated sections and this picker can never disagree about what is enabled.
 */
class ToolsMarketplaceDelegate(
    private val stateHandle: AgentEditorStateHandle,
    private val toolFavoritesRepository: ToolFavoritesRepository,
    private val capabilitiesDelegate: AgentCapabilitiesDelegate,
    /** Flips a built-in capability. Routed out because each has its own side effects. */
    private val setCapability: (String, Boolean) -> Unit,
    /** Adds/removes a plugin tool id on the agent. */
    private val toggleTool: (String) -> Unit,
    /** Adds/removes one MCP tool by name. */
    private val toggleMcpTool: (String) -> Unit,
) {

    init {
        stateHandle.scope.launch {
            combine(
                toolFavoritesRepository.favorites,
                toolFavoritesRepository.isSupported,
            ) { favorites, supported -> favorites.map { it.itemKey }.toSet() to supported }
                .collect { (keys, supported) ->
                    stateHandle.update {
                        copy(favoriteToolKeys = keys, areToolFavoritesSupported = supported)
                    }
                }
        }
    }

    fun openMarketplace() {
        stateHandle.update { copy(showToolsMarketplace = true) }
        // Refreshed on open rather than at editor construction: on a server without the routes
        // this is a wasted request, and the picker is the only place the pins are visible.
        stateHandle.scope.launch { toolFavoritesRepository.refresh() }
    }

    fun closeMarketplace() {
        stateHandle.update { copy(showToolsMarketplace = false, marketplaceQuery = "") }
    }

    fun onQueryChanged(query: String) {
        stateHandle.update { copy(marketplaceQuery = query) }
    }

    fun onFilterChanged(filter: MarketplaceFilter) {
        stateHandle.update { copy(marketplaceFilter = filter) }
    }

    /** Applies a catalog row's toggle to whichever delegate actually owns that kind's state. */
    fun toggleItem(item: MarketplaceItem) {
        val state = stateHandle.state
        when (item.kind) {
            MarketplaceKind.BUILTIN -> setCapability(item.id, !state.isBuiltinEnabled(item.id))
            MarketplaceKind.TOOL -> toggleTool(item.id)
            MarketplaceKind.MCP -> toggleMcpTool(item.id)
            MarketplaceKind.SKILL -> capabilitiesDelegate.onSkillSelectionToggled(item.id)
        }
    }

    fun toggleFavorite(item: MarketplaceItem) {
        stateHandle.scope.launch {
            val result = toolFavoritesRepository.toggle(item.kind.favoriteType, item.favoriteId)
            if (result is Result.Error) {
                // Surfaced rather than swallowed: the optimistic star has already rolled back,
                // so without a message the tap looks like it simply did nothing.
                Logger.d { "Tool favorite toggle failed: ${result.message}" }
                stateHandle.update { copy(error = result.message ?: "Could not update favorites") }
            }
        }
    }
}

/** Which slice of the catalog the picker is showing. */
enum class MarketplaceFilter {
    ALL,
    FAVORITES,
    BUILTIN,
    TOOLS,
    MCP,
    SKILLS,
}

/**
 * The full catalog, derived from reference data the editor already holds.
 *
 * Built in the UI state rather than stored, so it can never go stale against the lists it is
 * derived from. Only capabilities the server actually offers are listed — an agent cannot be
 * given a capability this deployment does not have, so offering it would be a dead row.
 */
fun AgentEditorUiState.marketplaceCatalog(): List<MarketplaceItem> = buildList {
    if (isCodeInterpreterAvailable) {
        add(builtin(ToolConstants.EXECUTE_CODE, MarketplaceBuiltinLabel.CODE_INTERPRETER))
    }
    add(builtin(ToolConstants.FILE_SEARCH, MarketplaceBuiltinLabel.FILE_SEARCH))
    if (isWebSearchAvailable) {
        add(builtin(ToolConstants.WEB_SEARCH, MarketplaceBuiltinLabel.WEB_SEARCH))
    }
    add(builtin(FILE_CONTEXT, MarketplaceBuiltinLabel.FILE_CONTEXT))

    availableTools.forEach { tool ->
        val id = tool.toolId ?: tool.name ?: return@forEach
        add(
            MarketplaceItem(
                kind = MarketplaceKind.TOOL,
                id = id,
                name = tool.name ?: id,
                description = tool.description,
                iconUrl = tool.icon,
            ),
        )
    }

    mcpTools.forEach { tool ->
        add(
            MarketplaceItem(
                kind = MarketplaceKind.MCP,
                id = tool.name,
                name = tool.name,
                description = tool.description,
                serverName = tool.serverName,
            ),
        )
    }

    if (isSkillsAvailable) {
        availableSkills.forEach { skill ->
            add(
                MarketplaceItem(
                    kind = MarketplaceKind.SKILL,
                    id = skill.id,
                    name = skill.displayTitle ?: skill.name,
                    description = skill.description,
                ),
            )
        }
    }
}

private fun builtin(id: String, label: MarketplaceBuiltinLabel) = MarketplaceItem(
    kind = MarketplaceKind.BUILTIN,
    id = id,
    labelRes = label,
)

/** True when [item] is currently on the agent. */
fun AgentEditorUiState.isMarketplaceItemSelected(item: MarketplaceItem): Boolean =
    when (item.kind) {
        MarketplaceKind.BUILTIN -> isBuiltinEnabled(item.id)
        MarketplaceKind.TOOL -> item.id in selectedTools
        MarketplaceKind.MCP -> item.id in selectedMcpTools
        MarketplaceKind.SKILL -> item.id in selectedSkillIds
    }

internal fun AgentEditorUiState.isBuiltinEnabled(id: String): Boolean = when (id) {
    ToolConstants.EXECUTE_CODE -> codeInterpreterEnabled
    ToolConstants.FILE_SEARCH -> fileSearchEnabled
    ToolConstants.WEB_SEARCH -> webSearchEnabled
    FILE_CONTEXT -> fileContextEnabled
    else -> false
}

/**
 * Narrows [catalog] to what the picker should currently show.
 *
 * Filtering by favorites is intentionally not the same as sorting by them: with the star filter
 * off, pinned items still float to the top of their kind, so a pin remains useful in the full
 * list instead of only inside its own tab.
 */
fun filterMarketplace(
    catalog: List<MarketplaceItem>,
    query: String,
    filter: MarketplaceFilter,
    favoriteKeys: Set<String>,
    /** Resolved built-in labels, keyed by [MarketplaceItem.itemKey], so search can see them. */
    builtinLabels: Map<String, String> = emptyMap(),
): List<MarketplaceItem> {
    val trimmed = query.trim()
    return catalog
        .filter { item ->
            val matchesKind = when (filter) {
                MarketplaceFilter.ALL -> true
                MarketplaceFilter.FAVORITES -> item.favoriteKey in favoriteKeys
                MarketplaceFilter.BUILTIN -> item.kind == MarketplaceKind.BUILTIN
                MarketplaceFilter.TOOLS -> item.kind == MarketplaceKind.TOOL
                MarketplaceFilter.MCP -> item.kind == MarketplaceKind.MCP
                MarketplaceFilter.SKILLS -> item.kind == MarketplaceKind.SKILL
            }
            if (!matchesKind) return@filter false
            if (trimmed.isEmpty()) return@filter true
            // A built-in's visible label lives in resources, so it is matched by the caller
            // supplying [builtinLabels]; its raw id ("execute_code") is still searchable.
            item.name?.contains(trimmed, ignoreCase = true) == true ||
                builtinLabels[item.itemKey]?.contains(trimmed, ignoreCase = true) == true ||
                item.id.contains(trimmed, ignoreCase = true) ||
                item.description?.contains(trimmed, ignoreCase = true) == true ||
                item.serverName?.contains(trimmed, ignoreCase = true) == true
        }
        .sortedWith(
            compareBy(
                { it.kind.ordinal },
                { if (it.favoriteKey in favoriteKeys) 0 else 1 },
                { (it.name ?: builtinLabels[it.itemKey] ?: it.id).lowercase() },
            ),
        )
}

/** The key a favorite is stored under — server-scoped for MCP, item-scoped otherwise. */
val MarketplaceItem.favoriteKey: String
    get() = "${kind.favoriteType.wireName}:$favoriteId"

/** The `tool_resource` marker the editor uses for whole-file context. */
private const val FILE_CONTEXT = "context"
