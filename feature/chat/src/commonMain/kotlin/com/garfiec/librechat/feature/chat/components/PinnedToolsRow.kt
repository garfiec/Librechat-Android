package com.garfiec.librechat.feature.chat.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource

/**
 * Inline quick-toggle chips for the server's pinned tools (v0.8.7 `defaultPinnedTools`),
 * shown on the input bar so common tools are one tap instead of buried in the tools sheet.
 * Keys are pre-mapped and gated by the caller ([ChatUiState.pinnedToolChips]); selected
 * state reads from [enabledTools] (which includes the synthesized web_search/url_context).
 * Icon + label come from the shared [ephemeralToolMeta] so this can't drift from the sheet.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PinnedToolsRow(
    pinnedToolKeys: List<String>,
    enabledTools: Set<String>,
    onToggleTool: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        pinnedToolKeys.forEach { key ->
            val meta = ephemeralToolMeta(key) ?: return@forEach
            FilterChip(
                selected = key in enabledTools,
                onClick = { onToggleTool(key) },
                label = { Text(stringResource(meta.titleRes)) },
                leadingIcon = {
                    Icon(
                        imageVector = meta.icon,
                        contentDescription = null,
                        modifier = Modifier.size(FilterChipDefaults.IconSize),
                    )
                },
            )
        }
    }
}
