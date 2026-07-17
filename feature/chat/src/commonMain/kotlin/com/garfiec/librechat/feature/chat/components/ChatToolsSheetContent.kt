package com.garfiec.librechat.feature.chat.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.garfiec.librechat.core.common.ToolConstants
import com.garfiec.librechat.core.data.datastore.ContextBarPlacement
import com.garfiec.librechat.core.model.usage.ContextUsage
import com.garfiec.librechat.core.model.usage.TokenUsage
import com.garfiec.librechat.feature.chat.model.McpServerDisplayData
import com.garfiec.librechat.feature.chat.resources.*
import com.garfiec.librechat.feature.chat.resources.Res
import com.garfiec.librechat.feature.chat.viewmodel.ChatInputGates
import org.jetbrains.compose.resources.stringResource

/**
 * The scrollable body of the chat tools/attachment menu (the Options page), no sheet chrome.
 * Rendered by both [ChatOptionsBottomSheet] and the pull-up surface in `ChatScreen`.
 *
 * The Model / Model Parameters rows do *not* call [onDismiss] (the paged sheet swaps in place, the
 * pull-up hands off); every other row calls its action then [onDismiss].
 */
@Composable
fun ChatToolsSheetContent(
    enabledTools: Set<String>,
    onToggleTool: (String) -> Unit,
    mcpServers: List<McpServerDisplayData>,
    selectedMcpServerNames: Set<String>,
    onToggleMcpServer: (String) -> Unit,
    onAttachFiles: () -> Unit,
    onTakePhoto: () -> Unit,
    onPickPhotos: () -> Unit,
    onAttachFromServer: () -> Unit,
    onOpenModelParameters: () -> Unit,
    onOpenModelSelector: () -> Unit,
    selectedModelDisplay: String?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    isCodeInterpreterAvailable: Boolean = true,
    webSearchEnabled: Boolean = true,
    /** Whether to offer the Google-only URL Context toggle (active provider is Google/Gemini). */
    urlContextEnabled: Boolean = false,
    runCodeEnabled: Boolean = true,
    fileSearchEnabled: Boolean = true,
    mcpServersEnabled: Boolean = true,
    /**
     * Server/endpoint feature gates for the sheet: model-select row, parameters row,
     * ephemeral tool controls (web search / code / file search / MCP), and the
     * Camera / Photos / Files attach controls. See [ChatInputGates].
     */
    gates: ChatInputGates = ChatInputGates(),
    /** Latest context-window usage snapshot; drives the optional context gauge above the model row. */
    contextUsage: ContextUsage? = null,
    /** Latest per-call token usage, for the gauge's expanded Input/Output breakdown rows. */
    tokenUsage: TokenUsage? = null,
    /** Server/version gate for the context gauge (`interface.contextUsage` AND backend ≥ 0.8.7). */
    contextUsageEnabled: Boolean = false,
    /** Where the user chose to surface the gauge; the sheet only renders it when [ContextBarPlacement.OPTIONS_SHEET]. */
    contextBarPlacement: ContextBarPlacement = ContextBarPlacement.OPTIONS_SHEET,
    /** Persisted expanded/collapsed state of the gauge's inline breakdown. */
    contextGaugeExpanded: Boolean = false,
    onContextGaugeExpandedChange: (Boolean) -> Unit = {},
    /** MCP sub-list expansion, hoisted so the paged sheet keeps it across a page swap (which drops this composable). */
    mcpExpanded: Boolean = false,
    onMcpExpandedChange: (Boolean) -> Unit = {},
    /** Scroll position, hoisted for the same reason as [mcpExpanded]. Defaulted for hosts that keep it composed. */
    scrollState: ScrollState = rememberScrollState(),
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            // Tall configurations exceed the sheet height, so the content scrolls.
            .verticalScroll(scrollState)
            .navigationBarsPadding()
            .padding(bottom = 16.dp),
    ) {
        // Top section: Camera, Photos, Files, Server cards — hidden when the selected
        // endpoint can't accept uploads (mirrors web's AttachFileChat gate). "Server"
        // attaches an already-uploaded file by reference (no re-upload).
        if (gates.fileUploadEnabled) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AttachmentOptionCard(
                    icon = Icons.Default.PhotoCamera,
                    label = stringResource(Res.string.tool_camera),
                    onClick = {
                        onTakePhoto()
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f),
                )
                AttachmentOptionCard(
                    icon = Icons.Default.PhotoLibrary,
                    label = stringResource(Res.string.tool_photos),
                    onClick = {
                        onPickPhotos()
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f),
                )
                AttachmentOptionCard(
                    icon = Icons.Default.AttachFile,
                    label = stringResource(Res.string.tool_files),
                    onClick = {
                        onAttachFiles()
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f),
                )
                AttachmentOptionCard(
                    icon = Icons.Default.CloudDownload,
                    label = stringResource(Res.string.tool_server_files),
                    onClick = {
                        onAttachFromServer()
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Context-usage gauge, surfaced here when the user picked the options-sheet placement.
        // Full-width; tapping expands the breakdown inline (no nested modal sheet).
        val sheetContextUsage = contextUsage
        if (contextBarPlacement == ContextBarPlacement.OPTIONS_SHEET &&
            contextUsageEnabled &&
            sheetContextUsage != null &&
            sheetContextUsage.usedTokens > 0
        ) {
            ContextUsageExpandableGauge(
                usage = sheetContextUsage,
                expanded = contextGaugeExpanded,
                onExpandedChange = onContextGaugeExpandedChange,
                tokenUsage = tokenUsage,
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 8.dp),
            )
        }

        // Model selector row — hidden when the server disables `interface.modelSelect`.
        if (gates.modelSelectEnabled) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .sheetRowRipple()
                    // No onDismiss — see the KDoc above.
                    .clickable { onOpenModelSelector() }
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.SmartToy,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(Res.string.tool_model),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = selectedModelDisplay ?: stringResource(Res.string.select_model),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Model Parameters — hidden when the server disables `interface.parameters`.
        if (gates.parametersEnabled) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .sheetRowRipple()
                    .clickable { onOpenModelParameters() }
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.Tune,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(Res.string.tool_model_parameters),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = stringResource(Res.string.tool_model_parameters_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
        Spacer(modifier = Modifier.height(8.dp))

        // Tool toggle items — ephemeral tools are hidden for the agents endpoint
        // (the agent uses its own configured tools), mirroring web's showEphemeralBadges.
        // Each tool's icon/label comes from the shared [ephemeralToolMeta]; the per-tool
        // enable gate stays inline (they differ per tool).
        if (gates.showEphemeralTools) {
            val toolRows = listOf(
                ToolConstants.WEB_SEARCH to webSearchEnabled,
                ToolConstants.URL_CONTEXT to urlContextEnabled,
                ToolConstants.CODE_INTERPRETER to (isCodeInterpreterAvailable && runCodeEnabled),
                ToolConstants.FILE_SEARCH to fileSearchEnabled,
            )
            toolRows.forEach { (toolKey, enabled) ->
                val meta = ephemeralToolMeta(toolKey)
                if (enabled && meta != null) {
                    ToolToggleRow(
                        icon = meta.icon,
                        title = stringResource(meta.titleRes),
                        subtitle = stringResource(meta.descriptionRes),
                        isEnabled = toolKey in enabledTools,
                        onToggle = { onToggleTool(toolKey) },
                    )
                }
            }
        }

        // MCP section — hidden when role denies MCP_SERVERS.USE, and for the agents
        // endpoint where dynamic MCP selections are silently ignored by the backend.
        if (gates.showEphemeralTools && mcpServersEnabled && mcpServers.isNotEmpty()) {
            val anyMcpSelected = selectedMcpServerNames.isNotEmpty()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .sheetRowRipple()
                    .clickable { onMcpExpandedChange(!mcpExpanded) }
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.Extension,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = if (anyMcpSelected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(Res.string.tool_mcp),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = stringResource(Res.string.tool_mcp_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (anyMcpSelected) {
                    Text(
                        text = "${selectedMcpServerNames.size}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                }
            }

            // MCP server sub-list
            if (mcpExpanded) {
                Column(
                    modifier = Modifier.padding(start = 40.dp),
                ) {
                    mcpServers.forEach { server ->
                        McpServerToggleRow(
                            server = server,
                            isSelected = server.name in selectedMcpServerNames,
                            onToggle = { onToggleMcpServer(server.name) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolToggleRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    isEnabled: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .sheetRowRipple()
            .clickable(onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 12.dp)
            .semantics {
                contentDescription = if (isEnabled) {
                    "$title enabled"
                } else {
                    "$title disabled"
                }
                role = Role.Switch
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = if (isEnabled) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = isEnabled,
            onCheckedChange = { onToggle() },
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.primary,
                checkedTrackColor = MaterialTheme.colorScheme.primaryContainer,
            ),
        )
    }
}

@Composable
private fun AttachmentOptionCard(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier.height(80.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun McpServerToggleRow(
    server: McpServerDisplayData,
    isSelected: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .sheetRowRipple()
            .clickable(onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Connection status indicator
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(
                    color = if (server.isConnected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                    shape = CircleShape,
                ),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = server.title ?: server.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val description = server.description
            if (!description.isNullOrBlank()) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Checkbox(
            checked = isSelected,
            onCheckedChange = { onToggle() },
        )
    }
}
