package com.garfiec.librechat.feature.chat.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.garfiec.librechat.feature.chat.resources.Res
import com.garfiec.librechat.feature.chat.resources.action_archive
import com.garfiec.librechat.feature.chat.resources.action_duplicate
import com.garfiec.librechat.feature.chat.resources.action_rename
import com.garfiec.librechat.feature.chat.resources.action_search
import com.garfiec.librechat.feature.chat.resources.action_share
import com.garfiec.librechat.feature.chat.resources.action_show_all_media
import com.garfiec.librechat.feature.chat.resources.cd_comparison_enabled
import com.garfiec.librechat.feature.chat.resources.compare_models
import com.garfiec.librechat.feature.chat.resources.delete
import com.garfiec.librechat.feature.chat.resources.load_preset
import com.garfiec.librechat.feature.chat.resources.new_chat
import com.garfiec.librechat.feature.chat.resources.prompts_library
import com.garfiec.librechat.feature.chat.resources.save_as_preset
import org.jetbrains.compose.resources.stringResource

/**
 * The chat top bar's overflow menu, shared by the Android and iOS floating top bars so the items,
 * ordering, gating, and icons stay identical across platforms. Each action dismisses the menu
 * before running. Items are gated by the same `interface.*` flags the web header uses.
 */
@Composable
internal fun ChatOverflowMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    conversationId: String?,
    conversationTitle: String?,
    presetsEnabled: Boolean,
    promptsEnabled: Boolean,
    multiConvoEnabled: Boolean,
    sharedLinksEnabled: Boolean,
    isComparisonEnabled: Boolean,
    onOpenSearch: () -> Unit,
    onShowAllMedia: (() -> Unit)?,
    onLoadPreset: () -> Unit,
    onSavePreset: () -> Unit,
    onOpenPromptsLibrary: (() -> Unit)?,
    onToggleComparison: () -> Unit,
    onShare: () -> Unit,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(16.dp),
    ) {
        if (conversationId != null) {
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.action_search)) },
                onClick = {
                    onDismiss()
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
                    onDismiss()
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
                    onDismiss()
                    onLoadPreset()
                },
                leadingIcon = {
                    Icon(Icons.Outlined.FileOpen, contentDescription = null)
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.save_as_preset)) },
                onClick = {
                    onDismiss()
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
                    onDismiss()
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
                    onDismiss()
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
                text = conversationTitle ?: stringResource(Res.string.new_chat),
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
                        onDismiss()
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
                    onDismiss()
                    onRename()
                },
                leadingIcon = {
                    Icon(Icons.Outlined.Edit, contentDescription = null)
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.action_duplicate)) },
                onClick = {
                    onDismiss()
                    onDuplicate()
                },
                leadingIcon = {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = null)
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.action_archive)) },
                onClick = {
                    onDismiss()
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
                        stringResource(Res.string.delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                },
                onClick = {
                    onDismiss()
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
