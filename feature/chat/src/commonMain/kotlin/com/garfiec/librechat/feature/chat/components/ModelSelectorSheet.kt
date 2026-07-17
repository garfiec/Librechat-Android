package com.garfiec.librechat.feature.chat.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.outlined.Create
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.garfiec.librechat.core.common.EndpointConstants
import com.garfiec.librechat.core.data.datastore.StarredModelsDisplay
import com.garfiec.librechat.core.model.Agent
import com.garfiec.librechat.core.model.EndpointConfig
import com.garfiec.librechat.core.model.endpoint.KeyState
import com.garfiec.librechat.core.ui.components.EndpointIcon
import com.garfiec.librechat.core.ui.components.ErrorBanner
import com.garfiec.librechat.core.ui.components.LowProfileDragHandle
import com.garfiec.librechat.feature.chat.resources.*
import com.garfiec.librechat.feature.chat.resources.Res
import com.garfiec.librechat.feature.chat.util.FuzzyMatch
import com.garfiec.librechat.feature.chat.viewmodel.delegate.FavoritesDelegate
import com.garfiec.librechat.feature.chat.viewmodel.delegate.filterModelsByEndpoint
import org.jetbrains.compose.resources.stringResource

private val IconSize = 20.dp
private const val FUZZY_MATCH_THRESHOLD = 55

/** Sentinel group key for the optional "Starred" section's expand/collapse state. */
private const val STARRED_GROUP_KEY = "__starred__"

/** Row vertical padding for grouped list items vs. the denser starred-at-top section. */
private val ListItemVerticalPadding = 12.dp
private val StarredItemVerticalPadding = 6.dp

private fun fuzzyMatches(candidate: String, query: String): Boolean {
    // Short queries (1-2 chars) use substring matching for better UX
    if (query.length <= 2) return candidate.contains(query, ignoreCase = true)
    return FuzzyMatch.partialRatio(query, candidate) >= FUZZY_MATCH_THRESHOLD
}

/**
 * Returns the fuzzy match score (0-100) for [candidate] against [query].
 * For short queries (1-2 chars), returns 100 if substring matches, 0 otherwise.
 */
private fun fuzzyScore(candidate: String, query: String): Int {
    if (query.length <= 2) {
        return if (candidate.contains(query, ignoreCase = true)) 100 else 0
    }
    return FuzzyMatch.partialRatio(query, candidate)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSelectorSheet(
    endpointConfigs: Map<String, EndpointConfig>,
    availableModels: Map<String, List<String>>,
    agents: List<Agent>,
    selectedEndpoint: String?,
    selectedModel: String?,
    onModelSelect: (endpoint: String, model: String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    serverUrl: String = "",
    errorMessage: String? = null,
    onErrorDismiss: () -> Unit = {},
    favoriteAgentIds: Set<String> = emptySet(),
    favoriteModelKeys: Set<String> = emptySet(),
    onToggleAgentFavorite: ((agentId: String) -> Unit)? = null,
    onToggleModelFavorite: ((endpoint: String, model: String) -> Unit)? = null,
    /**
     * Mobile-only preference controlling whether pinned items also appear in a
     * dedicated section at the top of the sheet. [StarredModelsDisplay.OFF] keeps the
     * historical behavior (favorites float to the top within their own group).
     */
    starredDisplay: StarredModelsDisplay = StarredModelsDisplay.OFF,
    /**
     * Per-endpoint user-provided-key state. Endpoints whose key is [KeyState.Unset]
     * or [KeyState.Expired] render a greyed group with a "Set API Key" CTA. Absent
     * keys (built-in endpoints) and [KeyState.Loading] / [KeyState.Set] all
     * fail-open to the normal selectable rendering.
     */
    endpointKeyStates: Map<String, KeyState> = emptyMap(),
    /**
     * Invoked with the endpoint name when the user taps the "Set API Key" CTA on
     * a greyed group. Implementations should navigate to Settings → Provider API Keys.
     */
    onSetApiKey: (endpointName: String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        dragHandle = { LowProfileDragHandle() },
        sheetState = sheetState,
        modifier = modifier,
    ) {
        ModelSelectorSheetContent(
            endpointConfigs = endpointConfigs,
            availableModels = availableModels,
            agents = agents,
            selectedEndpoint = selectedEndpoint,
            selectedModel = selectedModel,
            onModelSelect = onModelSelect,
            onSetApiKey = onSetApiKey,
            modifier = Modifier.fillMaxSize(),
            serverUrl = serverUrl,
            errorMessage = errorMessage,
            onErrorDismiss = onErrorDismiss,
            favoriteAgentIds = favoriteAgentIds,
            favoriteModelKeys = favoriteModelKeys,
            onToggleAgentFavorite = onToggleAgentFavorite,
            onToggleModelFavorite = onToggleModelFavorite,
            starredDisplay = starredDisplay,
            endpointKeyStates = endpointKeyStates,
        )
    }
}

/**
 * The model selector body (header, search, grouped list), no sheet chrome. Rendered by the
 * standalone [ModelSelectorSheet] and by `ChatOptionsBottomSheet`'s selector page. Has no
 * `onDismiss` — each host decides what selection does (swap to Options vs. dismiss).
 */
@Composable
fun ModelSelectorSheetContent(
    endpointConfigs: Map<String, EndpointConfig>,
    availableModels: Map<String, List<String>>,
    agents: List<Agent>,
    selectedEndpoint: String?,
    selectedModel: String?,
    onModelSelect: (endpoint: String, model: String) -> Unit,
    /**
     * Invoked with the endpoint name when the user taps the "Set API Key" CTA on
     * a greyed group. Implementations should navigate to Settings → Provider API Keys.
     */
    onSetApiKey: (endpointName: String) -> Unit,
    modifier: Modifier = Modifier,
    serverUrl: String = "",
    errorMessage: String? = null,
    onErrorDismiss: () -> Unit = {},
    favoriteAgentIds: Set<String> = emptySet(),
    favoriteModelKeys: Set<String> = emptySet(),
    onToggleAgentFavorite: ((agentId: String) -> Unit)? = null,
    onToggleModelFavorite: ((endpoint: String, model: String) -> Unit)? = null,
    /**
     * Mobile-only preference controlling whether pinned items also appear in a
     * dedicated section at the top of the sheet. [StarredModelsDisplay.OFF] keeps the
     * historical behavior (favorites float to the top within their own group).
     */
    starredDisplay: StarredModelsDisplay = StarredModelsDisplay.OFF,
    /**
     * Per-endpoint user-provided-key state. Endpoints whose key is [KeyState.Unset]
     * or [KeyState.Expired] render a greyed group with a "Set API Key" CTA. Absent
     * keys (built-in endpoints) and [KeyState.Loading] / [KeyState.Set] all
     * fail-open to the normal selectable rendering.
     */
    endpointKeyStates: Map<String, KeyState> = emptyMap(),
    /** Rendered above the error banner and title; the paged host passes a back-arrow row. */
    header: (@Composable () -> Unit)? = null,
) {
    var searchQuery by remember { mutableStateOf("") }
    // Only user toggles land here; absent keys default collapsed via `== true` reads below. Not
    // seeded — the sheet can mount before models/agents load, and a seed would miss late arrivals.
    val expandedGroups = remember { mutableStateMapOf<String, Boolean>() }
    val isSearching = searchQuery.isNotBlank()

    // Filter to only show models for endpoints the user's server has enabled
    val filteredByEndpoint = remember(availableModels, endpointConfigs) {
        filterModelsByEndpoint(availableModels, endpointConfigs)
    }

    // Filter agents by search query (fuzzy matching), sorted by score when searching.
    // When not searching, pinned agents (v0.8.5+) sort to the top of the list.
    val filteredAgents = remember(agents, searchQuery, favoriteAgentIds) {
        val base = if (!isSearching) {
            agents
        } else if (searchQuery.length <= 2) {
            agents.filter { agent ->
                val name = agent.name ?: agent.id
                fuzzyMatches(name, searchQuery)
            }
        } else {
            agents.map { agent ->
                val name = agent.name ?: agent.id
                agent to fuzzyScore(name, searchQuery)
            }
                .filter { (_, score) -> score >= FUZZY_MATCH_THRESHOLD }
                .sortedByDescending { (_, score) -> score }
                .map { (agent, _) -> agent }
        }
        if (isSearching) {
            base
        } else {
            base.sortedByDescending { it.id in favoriteAgentIds }
        }
    }

    // Optional dedicated "Starred" section (mobile-only). Shown only when not searching
    // (search already reorders by relevance) and the preference is not OFF. Starred items
    // are also still rendered within their own groups below — nothing is removed.
    val showStarred = !isSearching && starredDisplay != StarredModelsDisplay.OFF
    // favoriteAgentIds / favoriteModelKeys are LinkedHashSets from FavoritesDelegate.publish,
    // so iterating them preserves the server-side pin order.
    val agentsById = remember(agents) { agents.associateBy { it.id } }
    val starredAgents = remember(agentsById, favoriteAgentIds, showStarred) {
        if (!showStarred) emptyList() else favoriteAgentIds.mapNotNull { agentsById[it] }
    }
    val starredModels = remember(filteredByEndpoint, favoriteModelKeys, showStarred) {
        if (!showStarred) {
            emptyList()
        } else {
            favoriteModelKeys.mapNotNull { key ->
                val (endpoint, model) = FavoritesDelegate.parseFavoriteModelKey(key) ?: return@mapNotNull null
                // Only surface favorites whose endpoint+model are actually available.
                if (filteredByEndpoint[endpoint]?.contains(model) == true) endpoint to model else null
            }
        }
    }
    val hasStarred = starredAgents.isNotEmpty() || starredModels.isNotEmpty()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .imePadding()
            .padding(bottom = 32.dp),
    ) {
        header?.invoke()
        // Banner applies its own 16dp inset, so it sits outside the Column's
        // horizontal padding to line up with the other elements.
        if (errorMessage != null) {
            ErrorBanner(message = errorMessage, onDismiss = onErrorDismiss)
        }
        Text(
            text = stringResource(Res.string.select_a_model),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
        )

        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Search models...") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            singleLine = true,
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(
                            imageVector = Icons.Default.Clear,
                            contentDescription = stringResource(Res.string.cd_clear_search),
                        )
                    }
                }
            },
        )

        Spacer(modifier = Modifier.height(12.dp))

        LazyColumn(
            modifier = Modifier.weight(1f),
        ) {
            // Optional "Starred" section at the very top (mobile-only). Starred items
            // are duplicated here — they still render in their own groups below.
            if (showStarred && hasStarred) {
                // GROUPED renders a collapsible header and respects its toggle; TOP is a
                // flat, always-shown list closed off with a divider. OFF never reaches here.
                val starredItemsVisible = when (starredDisplay) {
                    StarredModelsDisplay.GROUPED -> {
                        val expanded = expandedGroups[STARRED_GROUP_KEY] == true
                        item(key = "header_starred") {
                            EndpointGroupHeader(
                                endpointName = STARRED_GROUP_KEY,
                                displayLabel = stringResource(Res.string.starred_group),
                                modelCount = starredAgents.size + starredModels.size,
                                isExpanded = expanded,
                                iconUrl = null,
                                leadingIcon = Icons.Default.Star,
                                onToggle = { expandedGroups[STARRED_GROUP_KEY] = !expanded },
                            )
                        }
                        expanded
                    }
                    StarredModelsDisplay.TOP -> true
                    StarredModelsDisplay.OFF -> false
                }
                if (starredItemsVisible) {
                    // The flat TOP list reads as a dense quick-access strip; GROUPED keeps
                    // the standard row rhythm since it sits behind a collapsible header.
                    val starredPadding = if (starredDisplay == StarredModelsDisplay.TOP) {
                        StarredItemVerticalPadding
                    } else {
                        ListItemVerticalPadding
                    }
                    items(starredAgents, key = { "starred_agent_${it.id}" }, contentType = { "agent" }) { agent ->
                        AgentListItem(
                            agent = agent,
                            isSelected = selectedEndpoint == EndpointConstants.AGENTS && agent.id == selectedModel,
                            serverUrl = serverUrl,
                            onClick = { onModelSelect(EndpointConstants.AGENTS, agent.id) },
                            isFavorite = true,
                            onToggleFavorite = onToggleAgentFavorite?.let { toggle -> { toggle(agent.id) } },
                            verticalPadding = starredPadding,
                        )
                    }
                    items(
                        starredModels,
                        key = { (endpoint, model) -> "starred_model_$endpoint::$model" },
                        contentType = { "model" },
                    ) { (endpoint, model) ->
                        ModelListItem(
                            model = model,
                            isSelected = endpoint == selectedEndpoint && model == selectedModel,
                            isFavorite = true,
                            onClick = { onModelSelect(endpoint, model) },
                            onToggleFavorite = onToggleModelFavorite?.let { toggle -> { toggle(endpoint, model) } },
                            verticalPadding = starredPadding,
                        )
                    }
                }
                if (starredDisplay == StarredModelsDisplay.TOP) {
                    item(key = "starred_divider") {
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    }
                }
            }

            // "My Agents" group (shown first, like the web frontend)
            if (filteredAgents.isNotEmpty()) {
                val agentsExpanded = isSearching || expandedGroups[EndpointConstants.AGENTS] == true
                item(key = "header_agents") {
                    EndpointGroupHeader(
                        endpointName = EndpointConstants.AGENTS,
                        displayLabel = "My Agents",
                        modelCount = filteredAgents.size,
                        isExpanded = agentsExpanded,
                        iconUrl = null,
                        onToggle = { if (!isSearching) expandedGroups[EndpointConstants.AGENTS] = !agentsExpanded },
                    )
                }
                if (agentsExpanded) {
                    items(filteredAgents, key = { "agents_${it.id}" }, contentType = { "agent" }) { agent ->
                        AgentListItem(
                            agent = agent,
                            isSelected = selectedEndpoint == EndpointConstants.AGENTS && agent.id == selectedModel,
                            serverUrl = serverUrl,
                            onClick = { onModelSelect(EndpointConstants.AGENTS, agent.id) },
                            isFavorite = agent.id in favoriteAgentIds,
                            onToggleFavorite = onToggleAgentFavorite?.let { toggle -> { toggle(agent.id) } },
                        )
                    }
                }
            }

            // Endpoint model groups
            filteredByEndpoint.forEach { (endpointName, models) ->
                val baseFiltered = if (!isSearching) {
                    models
                } else if (searchQuery.length <= 2) {
                    models.filter { fuzzyMatches(it, searchQuery) }
                } else {
                    models.map { model -> model to fuzzyScore(model, searchQuery) }
                        .filter { (_, score) -> score >= FUZZY_MATCH_THRESHOLD }
                        .sortedByDescending { (_, score) -> score }
                        .map { (model, _) -> model }
                }
                // Pinned models (v0.8.5+) sort to the top when not searching.
                val filteredModels = if (isSearching) {
                    baseFiltered
                } else {
                    baseFiltered.sortedByDescending { "$endpointName::$it" in favoriteModelKeys }
                }
                if (filteredModels.isNotEmpty()) {
                    val config = endpointConfigs[endpointName]
                    val displayLabel = config?.modelDisplayLabel ?: endpointName
                    // Auto-expand while searching so matches in collapsed groups are visible;
                    // otherwise use manual toggle state.
                    val isExpanded = isSearching || expandedGroups[endpointName] == true
                    // Endpoints with userProvide=true and Unset/Expired key render disabled
                    // with a "Set API Key" CTA. Loading and absent states fail-open.
                    val keyState = endpointKeyStates[endpointName]
                    val needsKey = keyState is KeyState.Unset || keyState is KeyState.Expired
                    val effectiveExpanded = isExpanded && !needsKey
                    item(key = "header_$endpointName") {
                        EndpointGroupHeader(
                            endpointName = endpointName,
                            displayLabel = displayLabel,
                            modelCount = filteredModels.size,
                            isExpanded = effectiveExpanded,
                            iconUrl = config?.iconURL,
                            onToggle = { if (!isSearching) expandedGroups[endpointName] = !isExpanded },
                            needsKey = needsKey,
                            onSetApiKey = { onSetApiKey(endpointName) },
                        )
                    }
                    if (effectiveExpanded) {
                        items(filteredModels, key = { "${endpointName}_$it" }, contentType = { "model" }) { model ->
                            ModelListItem(
                                model = model,
                                isSelected = endpointName == selectedEndpoint && model == selectedModel,
                                isFavorite = "$endpointName::$model" in favoriteModelKeys,
                                onClick = { onModelSelect(endpointName, model) },
                                onToggleFavorite = onToggleModelFavorite?.let { toggle -> { toggle(endpointName, model) } },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EndpointGroupHeader(
    endpointName: String,
    displayLabel: String,
    modelCount: Int,
    isExpanded: Boolean,
    iconUrl: String?,
    onToggle: () -> Unit,
    needsKey: Boolean = false,
    onSetApiKey: () -> Unit = {},
    /** When set, renders this vector instead of an [EndpointIcon] (used by the Starred group). */
    leadingIcon: ImageVector? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Drop the clickable modifier entirely when needsKey — Compose's
            // `clickable(enabled = false)` still consumes touch events and
            // announces "disabled" to TalkBack/VoiceOver. Only the inner
            // SetApiKeyChip remains interactive in that branch.
            .then(
                if (needsKey) {
                    Modifier.padding(horizontal = 4.dp)
                } else {
                    Modifier.sheetRowRipple().clickable(onClick = onToggle)
                },
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Disabled icon + label use Material's standard 0.38 disabled alpha. The
        // CTA chip below stays full-opacity so the action remains discoverable.
        val labelAlpha = if (needsKey) 0.38f else 1f
        if (leadingIcon != null) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .size(IconSize)
                    .alpha(labelAlpha),
            )
        } else {
            EndpointIcon(
                endpointName = endpointName,
                iconUrl = iconUrl,
                size = IconSize,
                contentDescription = "$endpointName icon",
                glyphTint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.alpha(labelAlpha),
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "$displayLabel ($modelCount)",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .weight(1f)
                .alpha(labelAlpha),
        )
        if (needsKey) {
            SetApiKeyChip(
                endpointLabel = displayLabel,
                onClick = onSetApiKey,
            )
        } else {
            Icon(
                imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = stringResource(
                    if (isExpanded) Res.string.cd_collapse_section else Res.string.cd_expand_section,
                    displayLabel,
                ),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/**
 * Mirrors LibreChat web's "Set API Key" pill on greyed endpoint rows. Tapping
 * navigates to Settings → Provider API Keys with the endpoint pre-targeted.
 */
@Composable
private fun SetApiKeyChip(
    endpointLabel: String,
    onClick: () -> Unit,
) {
    val label = stringResource(Res.string.set_api_key_action)
    val cd = stringResource(Res.string.cd_set_api_key, endpointLabel)
    TextButton(onClick = onClick) {
        Icon(
            imageVector = Icons.Outlined.Settings,
            contentDescription = cd,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(IconSize),
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun LazyItemScope.ModelListItem(
    model: String,
    isSelected: Boolean,
    isFavorite: Boolean,
    onClick: () -> Unit,
    onToggleFavorite: (() -> Unit)?,
    verticalPadding: Dp = ListItemVerticalPadding,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .sheetRowRipple()
            .clickable(onClick = onClick)
            .padding(vertical = verticalPadding, horizontal = 12.dp)
            .animateItem(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = model,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        if (isSelected) {
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                Icons.Default.Check,
                contentDescription = stringResource(Res.string.cd_selected),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        if (onToggleFavorite != null) {
            Spacer(modifier = Modifier.width(4.dp))
            FavoriteStarButton(
                isFavorite = isFavorite,
                onToggle = onToggleFavorite,
            )
        }
    }
}

@Composable
private fun LazyItemScope.AgentListItem(
    agent: Agent,
    isSelected: Boolean,
    serverUrl: String,
    onClick: () -> Unit,
    isFavorite: Boolean = false,
    onToggleFavorite: (() -> Unit)? = null,
    verticalPadding: Dp = ListItemVerticalPadding,
) {
    val agentName = agent.name ?: agent.id
    val resolvedAvatarUrl = agent.avatarUrl?.let { url ->
        if (url.startsWith("http")) url else "$serverUrl$url"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .sheetRowRipple()
            .clickable(onClick = onClick)
            .padding(vertical = verticalPadding, horizontal = 12.dp)
            .animateItem(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Agent avatar
        if (resolvedAvatarUrl != null) {
            AsyncImage(
                model = resolvedAvatarUrl,
                contentDescription = "$agentName avatar",
                modifier = Modifier
                    .size(IconSize)
                    .clip(CircleShape),
                contentScale = ContentScale.Crop,
            )
        } else {
            Icon(
                imageVector = Icons.Outlined.Create,
                contentDescription = "$agentName icon",
                modifier = Modifier.size(IconSize),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = agentName,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        if (agent.isPublic == true) {
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                imageVector = Icons.Outlined.Public,
                contentDescription = stringResource(Res.string.cd_public_agent),
                modifier = Modifier.size(16.dp),
                tint = Color(0xFF4CAF50),
            )
        }
        if (isSelected) {
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                Icons.Default.Check,
                contentDescription = stringResource(Res.string.cd_selected),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        if (onToggleFavorite != null) {
            Spacer(modifier = Modifier.width(4.dp))
            FavoriteStarButton(isFavorite = isFavorite, onToggle = onToggleFavorite)
        }
    }
}

@Composable
private fun FavoriteStarButton(
    isFavorite: Boolean,
    onToggle: () -> Unit,
) {
    IconButton(onClick = onToggle, modifier = Modifier.size(32.dp)) {
        Icon(
            imageVector = if (isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
            contentDescription = stringResource(
                if (isFavorite) Res.string.cd_unpin_favorite else Res.string.cd_pin_favorite,
            ),
            tint = if (isFavorite) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(18.dp),
        )
    }
}
