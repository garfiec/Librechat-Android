package com.garfiec.librechat.feature.agents.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import com.garfiec.librechat.feature.agents.components.model.MarketplaceBuiltinLabel
import com.garfiec.librechat.feature.agents.components.model.MarketplaceItem
import com.garfiec.librechat.feature.agents.components.model.MarketplaceKind
import com.garfiec.librechat.feature.agents.resources.Res
import com.garfiec.librechat.feature.agents.resources.builtin_code_interpreter
import com.garfiec.librechat.feature.agents.resources.builtin_code_interpreter_desc
import com.garfiec.librechat.feature.agents.resources.builtin_file_context
import com.garfiec.librechat.feature.agents.resources.builtin_file_context_desc
import com.garfiec.librechat.feature.agents.resources.builtin_file_search
import com.garfiec.librechat.feature.agents.resources.builtin_file_search_desc
import com.garfiec.librechat.feature.agents.resources.builtin_web_search
import com.garfiec.librechat.feature.agents.resources.builtin_web_search_desc
import com.garfiec.librechat.feature.agents.resources.cd_pin_tool
import com.garfiec.librechat.feature.agents.resources.cd_tool_icon
import com.garfiec.librechat.feature.agents.resources.cd_unpin_tool
import com.garfiec.librechat.feature.agents.resources.close
import com.garfiec.librechat.feature.agents.resources.tools_filter_all
import com.garfiec.librechat.feature.agents.resources.tools_filter_builtin
import com.garfiec.librechat.feature.agents.resources.tools_filter_favorites
import com.garfiec.librechat.feature.agents.resources.tools_filter_mcp
import com.garfiec.librechat.feature.agents.resources.tools_filter_skills
import com.garfiec.librechat.feature.agents.resources.tools_filter_tools
import com.garfiec.librechat.feature.agents.resources.tools_marketplace_description
import com.garfiec.librechat.feature.agents.resources.tools_marketplace_empty
import com.garfiec.librechat.feature.agents.resources.tools_marketplace_no_favorites
import com.garfiec.librechat.feature.agents.resources.tools_marketplace_no_matches
import com.garfiec.librechat.feature.agents.resources.tools_marketplace_search_hint
import com.garfiec.librechat.feature.agents.resources.tools_marketplace_title
import com.garfiec.librechat.feature.agents.viewmodel.delegate.MarketplaceFilter
import com.garfiec.librechat.feature.agents.viewmodel.delegate.favoriteKey
import com.garfiec.librechat.feature.agents.viewmodel.delegate.filterMarketplace
import org.jetbrains.compose.resources.stringResource

/**
 * The agent editor's one place to pick what an agent can do.
 *
 * Replaces the separate tool / MCP / skill pickers: those were three dialogs with three search
 * boxes over one conceptual list, and nothing about "give this agent web search, the Jira MCP
 * server, and the code-review skill" is three decisions. Kind survives as a filter chip and a
 * leading icon rather than as a modal boundary.
 *
 * Favorites are per row and optimistic; the star column is hidden entirely when the server has
 * no tool-favorites route, because a control that always fails is worse than an absent one.
 */
@Composable
fun ToolsMarketplaceDialog(
    catalog: List<MarketplaceItem>,
    selectedKeys: Set<String>,
    favoriteKeys: Set<String>,
    favoritesSupported: Boolean,
    query: String,
    filter: MarketplaceFilter,
    onQueryChange: (String) -> Unit,
    onFilterChange: (MarketplaceFilter) -> Unit,
    onToggleItem: (MarketplaceItem) -> Unit,
    onToggleFavorite: (MarketplaceItem) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Built-in labels are resources, so search has to be handed their resolved text — the
    // delegate has no way to read them.
    val builtinLabels = catalog
        .filter { it.labelRes != null }
        .associate { it.itemKey to stringResource(it.labelRes!!.titleRes()) }

    val visible = remember(catalog, query, filter, favoriteKeys, builtinLabels) {
        filterMarketplace(catalog, query, filter, favoriteKeys, builtinLabels)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = modifier.fillMaxSize().imePadding().padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 6.dp,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(
                            text = stringResource(Res.string.tools_marketplace_title),
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Text(
                            text = stringResource(Res.string.tools_marketplace_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, stringResource(Res.string.close))
                    }
                }

                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    placeholder = { Text(stringResource(Res.string.tools_marketplace_search_hint)) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                )

                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MarketplaceFilter.entries.forEach { entry ->
                        // The Favorites tab would be a dead end on a server that cannot store
                        // any, so it is not offered there at all.
                        if (entry == MarketplaceFilter.FAVORITES && !favoritesSupported) return@forEach
                        FilterChip(
                            selected = filter == entry,
                            onClick = { onFilterChange(entry) },
                            label = { Text(stringResource(entry.labelRes())) },
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))

                if (visible.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize().padding(32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = when {
                                query.isNotBlank() ->
                                    stringResource(Res.string.tools_marketplace_no_matches, query)

                                filter == MarketplaceFilter.FAVORITES ->
                                    stringResource(Res.string.tools_marketplace_no_favorites)

                                else -> stringResource(Res.string.tools_marketplace_empty)
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(visible, key = { it.itemKey }, contentType = { "marketplace_item" }) { item ->
                            MarketplaceRow(
                                item = item,
                                isSelected = item.itemKey in selectedKeys,
                                isFavorite = item.favoriteKey in favoriteKeys,
                                favoritesSupported = favoritesSupported,
                                onToggle = { onToggleItem(item) },
                                onToggleFavorite = { onToggleFavorite(item) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MarketplaceRow(
    item: MarketplaceItem,
    isSelected: Boolean,
    isFavorite: Boolean,
    favoritesSupported: Boolean,
    onToggle: () -> Unit,
    onToggleFavorite: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = item.name ?: item.labelRes?.let { stringResource(it.titleRes()) } ?: item.id
    val subtitle = item.description ?: item.labelRes?.let { stringResource(it.descriptionRes()) }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = if (isSelected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        },
        onClick = onToggle,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                val iconUrl = item.iconUrl
                if (iconUrl != null) {
                    AsyncImage(
                        model = iconUrl,
                        contentDescription = stringResource(Res.string.cd_tool_icon, title),
                        modifier = Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Icon(
                        imageVector = item.kind.icon(),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // The MCP server is shown instead of a description because which server a tool
                // came from is what disambiguates two identically-named tools.
                val secondary = item.serverName ?: subtitle
                if (!secondary.isNullOrBlank()) {
                    Text(
                        text = secondary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (favoritesSupported) {
                IconButton(onClick = onToggleFavorite) {
                    Icon(
                        imageVector = if (isFavorite) Icons.Default.Star else Icons.Outlined.StarBorder,
                        contentDescription = stringResource(
                            if (isFavorite) Res.string.cd_unpin_tool else Res.string.cd_pin_tool,
                        ),
                        tint = if (isFavorite) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }

            if (isSelected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

private fun MarketplaceKind.icon(): ImageVector = when (this) {
    MarketplaceKind.BUILTIN -> Icons.Default.Extension
    MarketplaceKind.TOOL -> Icons.Default.Build
    MarketplaceKind.MCP -> Icons.Default.Hub
    MarketplaceKind.SKILL -> Icons.Default.School
}

private fun MarketplaceFilter.labelRes() = when (this) {
    MarketplaceFilter.ALL -> Res.string.tools_filter_all
    MarketplaceFilter.FAVORITES -> Res.string.tools_filter_favorites
    MarketplaceFilter.BUILTIN -> Res.string.tools_filter_builtin
    MarketplaceFilter.TOOLS -> Res.string.tools_filter_tools
    MarketplaceFilter.MCP -> Res.string.tools_filter_mcp
    MarketplaceFilter.SKILLS -> Res.string.tools_filter_skills
}

private fun MarketplaceBuiltinLabel.titleRes() = when (this) {
    MarketplaceBuiltinLabel.CODE_INTERPRETER -> Res.string.builtin_code_interpreter
    MarketplaceBuiltinLabel.FILE_SEARCH -> Res.string.builtin_file_search
    MarketplaceBuiltinLabel.WEB_SEARCH -> Res.string.builtin_web_search
    MarketplaceBuiltinLabel.FILE_CONTEXT -> Res.string.builtin_file_context
}

private fun MarketplaceBuiltinLabel.descriptionRes() = when (this) {
    MarketplaceBuiltinLabel.CODE_INTERPRETER -> Res.string.builtin_code_interpreter_desc
    MarketplaceBuiltinLabel.FILE_SEARCH -> Res.string.builtin_file_search_desc
    MarketplaceBuiltinLabel.WEB_SEARCH -> Res.string.builtin_web_search_desc
    MarketplaceBuiltinLabel.FILE_CONTEXT -> Res.string.builtin_file_context_desc
}
