package com.garfiec.librechat.feature.conversations.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.garfiec.librechat.core.model.Conversation
import com.garfiec.librechat.core.ui.components.LowProfileDragHandle
import com.garfiec.librechat.feature.conversations.resources.*
import com.garfiec.librechat.feature.conversations.resources.Res
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationActions(
    conversation: Conversation,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    onTags: () -> Unit = {},
    onShare: () -> Unit = {},
    onDuplicate: () -> Unit = {},
    onExport: () -> Unit = {},
    onBookmarkToggle: () -> Unit = {},
    isBookmarked: Boolean = false,
    showShareAction: Boolean = false,
    bookmarksEnabled: Boolean = true,
) {
    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeleteConfirmation by remember { mutableStateOf(false) }

    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        dragHandle = { LowProfileDragHandle() },
        modifier = modifier,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier.padding(bottom = 32.dp),
        ) {
            Text(
                text = conversation.title ?: stringResource(Res.string.new_chat),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                maxLines = 1,
            )

            ActionRow(
                icon = Icons.Default.Edit,
                label = stringResource(Res.string.rename),
                onClick = {
                    onDismiss()
                    showRenameDialog = true
                },
            )

            if (bookmarksEnabled) {
                ActionRow(
                    icon = if (isBookmarked) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                    label = if (isBookmarked) stringResource(Res.string.remove_bookmark) else stringResource(Res.string.bookmark),
                    iconTint = if (isBookmarked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    onClick = {
                        onDismiss()
                        onBookmarkToggle()
                    },
                )
            }

            ActionRow(
                icon = Icons.AutoMirrored.Filled.Label,
                label = stringResource(Res.string.tags),
                onClick = {
                    onDismiss()
                    onTags()
                },
            )

            if (showShareAction) {
                ActionRow(
                    icon = Icons.Default.Share,
                    label = stringResource(Res.string.share),
                    onClick = {
                        onDismiss()
                        onShare()
                    },
                )
            }

            ActionRow(
                icon = Icons.Default.ContentCopy,
                label = stringResource(Res.string.duplicate),
                onClick = {
                    onDismiss()
                    onDuplicate()
                },
            )

            ActionRow(
                icon = Icons.Default.FileDownload,
                label = stringResource(Res.string.export),
                onClick = {
                    onDismiss()
                    onExport()
                },
            )

            ActionRow(
                icon = Icons.Default.Archive,
                label = stringResource(Res.string.archive),
                onClick = {
                    onDismiss()
                    onArchive()
                },
            )

            ActionRow(
                icon = Icons.Default.Delete,
                label = stringResource(Res.string.delete),
                iconTint = MaterialTheme.colorScheme.error,
                labelColor = MaterialTheme.colorScheme.error,
                onClick = {
                    onDismiss()
                    showDeleteConfirmation = true
                },
            )
        }
    }

    if (showRenameDialog) {
        RenameDialog(
            currentTitle = conversation.title ?: "",
            onDismiss = { showRenameDialog = false },
            onConfirm = { newTitle ->
                showRenameDialog = false
                onRename(newTitle)
            },
        )
    }

    if (showDeleteConfirmation) {
        DeleteConfirmationDialog(
            conversationTitle = conversation.title ?: stringResource(Res.string.this_conversation),
            onDismiss = { showDeleteConfirmation = false },
            onConfirm = {
                showDeleteConfirmation = false
                onDelete()
            },
        )
    }
}

@Composable
private fun ActionRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    iconTint: Color = MaterialTheme.colorScheme.onSurface,
    labelColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = iconTint,
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = labelColor,
        )
    }
}

@Composable
internal fun RenameDialog(
    currentTitle: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var title by remember { mutableStateOf(currentTitle) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.rename_conversation)) },
        text = {
            Column(modifier = Modifier.imePadding()) {
                Text(
                    text = stringResource(Res.string.rename_dialog_message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(Res.string.title_label)) },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(title) },
                enabled = title.isNotBlank(),
            ) {
                Text(stringResource(Res.string.rename))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.cancel))
            }
        },
    )
}

@Composable
internal fun DeleteConfirmationDialog(
    conversationTitle: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.delete_conversation)) },
        text = {
            Text(
                text = stringResource(Res.string.delete_confirmation_message, conversationTitle),
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = stringResource(Res.string.delete),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.cancel))
            }
        },
    )
}
