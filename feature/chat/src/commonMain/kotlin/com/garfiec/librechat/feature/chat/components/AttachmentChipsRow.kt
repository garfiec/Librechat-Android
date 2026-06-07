package com.garfiec.librechat.feature.chat.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FindInPage
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.garfiec.librechat.core.common.ToolConstants
import com.garfiec.librechat.feature.chat.model.McpServerDisplayData
import com.garfiec.librechat.feature.chat.resources.*
import com.garfiec.librechat.feature.chat.resources.Res
import org.jetbrains.compose.resources.stringResource

/**
 * Floating row of Material 3 chips above the chat input field.
 * Shows indicator chips for attached files, enabled tools (Web Search, Code, File Search),
 * and selected MCP servers. Only visible when there is at least one chip to display.
 */
@Composable
fun AttachmentChipsRow(
    attachedFiles: List<AttachedFile>,
    enabledTools: Set<String>,
    onRemoveFile: (AttachedFile) -> Unit,
    modifier: Modifier = Modifier,
    mcpServers: List<McpServerDisplayData> = emptyList(),
    selectedMcpServerNames: Set<String> = emptySet(),
    /**
     * Whether ephemeral tool selections (web search / code / file search / MCP) should be
     * shown as chips. False for the agents endpoint, where the underlying selections are
     * retained but not sent (see [ChatUiState.showEphemeralTools]); only the display is
     * suppressed so switching back to a concrete model restores the chips. File chips are
     * unaffected — files aren't ephemeral tools.
     */
    showEphemeralTools: Boolean = true,
) {
    val hasFiles = attachedFiles.isNotEmpty()
    // Memoize ephemeral state once to avoid repeated guards and per-recomposition MCP filtering when tools are hidden.
    val ephemeral = remember(showEphemeralTools, enabledTools, mcpServers, selectedMcpServerNames) {
        if (!showEphemeralTools) {
            EphemeralChips()
        } else {
            EphemeralChips(
                hasWebSearch = ToolConstants.WEB_SEARCH in enabledTools,
                hasCode = ToolConstants.CODE_INTERPRETER in enabledTools,
                hasFileSearch = ToolConstants.FILE_SEARCH in enabledTools,
                selectedMcpServers = mcpServers.filter { it.name in selectedMcpServerNames },
            )
        }
    }
    val hasWebSearch = ephemeral.hasWebSearch
    val hasCode = ephemeral.hasCode
    val hasFileSearch = ephemeral.hasFileSearch
    val selectedMcpServers = ephemeral.selectedMcpServers
    val hasMcp = selectedMcpServers.isNotEmpty()
    val hasAnyChip = hasFiles || hasWebSearch || hasCode || hasFileSearch || hasMcp

    var showFilePreview by remember { mutableStateOf(false) }

    // Dismiss preview when all files are removed
    if (!hasFiles) {
        showFilePreview = false
    }

    AnimatedVisibility(
        visible = hasAnyChip,
        enter = expandVertically(expandFrom = Alignment.Bottom) + fadeIn(),
        exit = shrinkVertically(shrinkTowards = Alignment.Bottom) + fadeOut(),
        modifier = modifier,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Inline file preview row — appears ABOVE the chips row
            AnimatedVisibility(
                visible = showFilePreview && hasFiles,
                enter = expandVertically(expandFrom = Alignment.Bottom) + fadeIn(),
                exit = shrinkVertically(shrinkTowards = Alignment.Bottom) + fadeOut(),
            ) {
                InlineFilePreviewRow(
                    files = attachedFiles,
                    onRemoveFile = onRemoveFile,
                )
            }

            // Chips row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Files chip
                if (hasFiles) {
                    FilesChip(
                        files = attachedFiles,
                        onClick = { showFilePreview = !showFilePreview },
                    )
                }

                // Web Search chip
                if (hasWebSearch) {
                    ToolIndicatorChip(
                        label = stringResource(Res.string.tool_web_search),
                        icon = Icons.Default.TravelExplore,
                        semanticDescription = stringResource(Res.string.cd_web_search_enabled),
                    )
                }

                // Code chip
                if (hasCode) {
                    ToolIndicatorChip(
                        label = stringResource(Res.string.tool_code),
                        icon = Icons.Default.Code,
                        semanticDescription = stringResource(Res.string.cd_code_enabled),
                    )
                }

                // File Search chip
                if (hasFileSearch) {
                    ToolIndicatorChip(
                        label = stringResource(Res.string.tool_file_search),
                        icon = Icons.Default.FindInPage,
                        semanticDescription = stringResource(Res.string.cd_file_search_enabled),
                    )
                }

                // MCP server chips — one chip per selected server
                selectedMcpServers.forEach { server ->
                    ToolIndicatorChip(
                        label = server.title ?: server.name,
                        icon = Icons.Default.Extension,
                        semanticDescription = "${server.title ?: server.name} MCP server enabled",
                    )
                }
            }
        }
    }
}

/**
 * Chip for attached files showing count. Tapping toggles the inline preview row.
 */
@Composable
private fun FilesChip(
    files: List<AttachedFile>,
    onClick: () -> Unit,
) {
    AssistChip(
        onClick = onClick,
        label = {
            Text(
                text = stringResource(Res.string.files_count, files.size),
                style = MaterialTheme.typography.labelMedium,
            )
        },
        leadingIcon = {
            Icon(
                imageVector = Icons.Default.AttachFile,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
        },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            labelColor = MaterialTheme.colorScheme.onSecondaryContainer,
            leadingIconContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
        modifier = Modifier.semantics {
            contentDescription = "${files.size} files attached, tap to preview"
            role = Role.Button
        },
    )
}

/**
 * Display-only tool indicator chip. Shows that a tool or MCP server is enabled.
 * Not interactive — users manage tools via the tools bottom sheet.
 */
@Composable
private fun ToolIndicatorChip(
    label: String,
    icon: ImageVector,
    semanticDescription: String,
) {
    AssistChip(
        onClick = {},
        label = {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
            )
        },
        leadingIcon = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
        },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            labelColor = MaterialTheme.colorScheme.onSecondaryContainer,
            leadingIconContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
        modifier = Modifier.semantics {
            contentDescription = semanticDescription
        },
    )
}

/**
 * Inline row of file thumbnails/icons shown above the chips row.
 * Each preview has a remove (X) button.
 */
@Composable
private fun InlineFilePreviewRow(
    files: List<AttachedFile>,
    onRemoveFile: (AttachedFile) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 3.dp,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            files.forEach { file ->
                FilePreviewItem(
                    file = file,
                    onRemove = { onRemoveFile(file) },
                )
            }
        }
    }
}

/**
 * Individual file preview: image thumbnail or file icon with filename.
 * Has a small circular X button in the top-right corner.
 */
@Composable
private fun FilePreviewItem(
    file: AttachedFile,
    onRemove: () -> Unit,
) {
    val attachedFileCd = stringResource(Res.string.cd_attached_file, file.name)
    Box(
        modifier = Modifier
            .size(56.dp)
            .semantics {
                contentDescription = attachedFileCd
            },
    ) {
        if (file.isImage) {
            AsyncImage(
                model = file.uri,
                contentDescription = stringResource(Res.string.cd_preview_file, file.name),
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop,
            )
        } else {
            Column(
                modifier = Modifier
                    .size(56.dp)
                    .background(
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        shape = RoundedCornerShape(8.dp),
                    )
                    .padding(4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.InsertDriveFile,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = file.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        val progress = file.uploadProgress
        val inFlight = file.fileId == null && !file.uploadFailed
        if (inFlight) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f)),
                contentAlignment = Alignment.Center,
            ) {
                if (progress != null) {
                    CircularProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.size(28.dp),
                        strokeWidth = 3.dp,
                        color = Color.White,
                        trackColor = Color.White.copy(alpha = 0.3f),
                    )
                } else {
                    CircularProgressIndicator(
                        modifier = Modifier.size(28.dp),
                        strokeWidth = 3.dp,
                        color = Color.White,
                        trackColor = Color.White.copy(alpha = 0.3f),
                    )
                }
            }
        } else if (file.uploadFailed) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.error.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.ErrorOutline,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                    tint = MaterialTheme.colorScheme.onError,
                )
            }
        }

        // Remove button
        IconButton(
            onClick = onRemove,
            modifier = Modifier
                .size(20.dp)
                .align(Alignment.TopEnd),
            colors = IconButtonDefaults.iconButtonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ),
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = stringResource(Res.string.cd_remove_file, file.name),
                modifier = Modifier.size(12.dp),
            )
        }
    }
}

private data class EphemeralChips(
    val hasWebSearch: Boolean = false,
    val hasCode: Boolean = false,
    val hasFileSearch: Boolean = false,
    val selectedMcpServers: List<McpServerDisplayData> = emptyList(),
)
