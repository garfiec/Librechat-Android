package com.garfiec.librechat.feature.chat.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Compare
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.SaveAs
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.garfiec.librechat.feature.chat.components.TempChatToggle
import com.garfiec.librechat.feature.chat.resources.*
import com.garfiec.librechat.feature.chat.resources.Res
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatTopBar(
    onLoadPreset: () -> Unit,
    onSavePreset: () -> Unit,
    onOpenDrawer: (() -> Unit)?,
    modifier: Modifier = Modifier,
    onOpenSearch: () -> Unit = {},
    onOpenPromptsLibrary: (() -> Unit)? = null,
    onShowAllMedia: (() -> Unit)? = null,
    promptsEnabled: Boolean = true,
    presetsEnabled: Boolean = true,
    multiConvoEnabled: Boolean = true,
    isTemporaryChat: Boolean = false,
    onToggleTemporaryChat: () -> Unit = {},
    showTempChatToggle: Boolean = false,
    isComparisonEnabled: Boolean = false,
    onToggleComparison: () -> Unit = {},
    conversationId: String? = null,
    conversationTitle: String? = null,
    sharedLinksEnabled: Boolean = false,
    onShare: () -> Unit = {},
    onRename: () -> Unit = {},
    onDuplicate: () -> Unit = {},
    onArchive: () -> Unit = {},
    onDelete: () -> Unit = {},
) {
    var showOverflowMenu by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onOpenDrawer != null) {
            IconButton(onClick = onOpenDrawer) {
                Icon(
                    imageVector = Icons.Default.Menu,
                    contentDescription = "Open navigation drawer",
                )
            }
        }

        // Show the conversation title when viewing an existing conversation. The header
        // intentionally has no model selector — model/params stay reachable from the
        // composer "+" menu (tools sheet), a deliberate mobile decluttering choice that
        // diverges from web's header model selector.
        if (conversationId != null && !conversationTitle.isNullOrBlank()) {
            Text(
                text = conversationTitle,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.width(4.dp))
        } else {
            Spacer(modifier = Modifier.weight(1f))
        }

        if (showTempChatToggle) {
            TempChatToggle(
                isTemporary = isTemporaryChat,
                onToggle = onToggleTemporaryChat,
            )
        }
        Box {
            IconButton(onClick = { showOverflowMenu = true }) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = stringResource(Res.string.cd_more_options),
                )
            }
            DropdownMenu(
                expanded = showOverflowMenu,
                onDismissRequest = { showOverflowMenu = false },
                shape = RoundedCornerShape(16.dp),
            ) {
                if (conversationId != null) {
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.action_search)) },
                        onClick = {
                            showOverflowMenu = false
                            onOpenSearch()
                        },
                        leadingIcon = {
                            Icon(Icons.Default.Search, contentDescription = null)
                        },
                    )
                }
                if (onShowAllMedia != null) {
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.action_show_all_media)) },
                        onClick = {
                            showOverflowMenu = false
                            onShowAllMedia()
                        },
                        leadingIcon = {
                            Icon(Icons.Outlined.PhotoLibrary, contentDescription = null)
                        },
                    )
                }
                // Preset load/save — hidden when the server disables `interface.presets`
                // (or `interface.modelSelect`), matching web's Header.tsx presets menu.
                if (presetsEnabled) {
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.load_preset)) },
                        onClick = {
                            showOverflowMenu = false
                            onLoadPreset()
                        },
                        leadingIcon = {
                            Icon(Icons.Outlined.FileOpen, contentDescription = null)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.save_as_preset)) },
                        onClick = {
                            showOverflowMenu = false
                            onSavePreset()
                        },
                        leadingIcon = {
                            Icon(Icons.Outlined.SaveAs, contentDescription = null)
                        },
                    )
                }
                if (onOpenPromptsLibrary != null && promptsEnabled) {
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.prompts_library)) },
                        onClick = {
                            showOverflowMenu = false
                            onOpenPromptsLibrary()
                        },
                        leadingIcon = {
                            Icon(Icons.Outlined.AutoAwesome, contentDescription = null)
                        },
                    )
                }
                if (multiConvoEnabled) {
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = stringResource(Res.string.compare_models),
                                    modifier = Modifier.weight(1f),
                                )
                                if (isComparisonEnabled) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Icon(
                                        imageVector = Icons.Filled.Check,
                                        contentDescription = stringResource(Res.string.cd_comparison_enabled),
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        },
                        onClick = {
                            showOverflowMenu = false
                            onToggleComparison()
                        },
                        leadingIcon = {
                            Icon(Icons.Outlined.Compare, contentDescription = null)
                        },
                    )
                }
                if (conversationId != null) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    Text(
                        text = conversationTitle ?: "New Chat",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    if (sharedLinksEnabled) {
                        DropdownMenuItem(
                            text = { Text(stringResource(Res.string.action_share)) },
                            onClick = {
                                showOverflowMenu = false
                                onShare()
                            },
                            leadingIcon = {
                                Icon(Icons.Outlined.Share, contentDescription = null)
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.action_rename)) },
                        onClick = {
                            showOverflowMenu = false
                            onRename()
                        },
                        leadingIcon = {
                            Icon(Icons.Outlined.Edit, contentDescription = null)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.action_duplicate)) },
                        onClick = {
                            showOverflowMenu = false
                            onDuplicate()
                        },
                        leadingIcon = {
                            Icon(Icons.Outlined.ContentCopy, contentDescription = null)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.action_archive)) },
                        onClick = {
                            showOverflowMenu = false
                            onArchive()
                        },
                        leadingIcon = {
                            Icon(Icons.Outlined.Archive, contentDescription = null)
                        },
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    DropdownMenuItem(
                        text = {
                            Text(
                                "Delete",
                                color = MaterialTheme.colorScheme.error,
                            )
                        },
                        onClick = {
                            showOverflowMenu = false
                            onDelete()
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Outlined.DeleteOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                        },
                    )
                }
            }
        }
    }
}
