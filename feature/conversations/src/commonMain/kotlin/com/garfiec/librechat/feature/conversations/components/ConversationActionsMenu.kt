package com.garfiec.librechat.feature.conversations.components

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.garfiec.librechat.feature.conversations.resources.*
import com.garfiec.librechat.feature.conversations.resources.Res
import org.jetbrains.compose.resources.stringResource

// Rounded to match the app's other surfaces (dialogs/sheets) rather than the menu default's
// near-square corners.
private val MenuShape = RoundedCornerShape(16.dp)

/**
 * Anchored dropdown variant of [ConversationActions]. Same action set, but presented as a
 * [DropdownMenu] (web-style) instead of a bottom sheet — used by the navigation drawer's
 * long-press menu. Must be placed inside the anchor's layout (e.g. the conversation row's Box)
 * so the menu positions itself at the row. Tags/Export are delegated to the caller (which hosts
 * the pickers), mirroring [ConversationActions].
 *
 * Rename/Delete only *request* their confirmation dialogs via [onRenameRequest]/[onDeleteRequest];
 * the caller hosts a single [ConversationActionDialogs] so the dialog survives this menu leaving
 * composition (the drawer composes the menu only for the row whose menu is open).
 */
@Composable
fun ConversationActionsMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    title: String,
    onRenameRequest: () -> Unit,
    onArchive: () -> Unit,
    onDeleteRequest: () -> Unit,
    modifier: Modifier = Modifier,
    onTags: () -> Unit = {},
    onShare: () -> Unit = {},
    onDuplicate: (String) -> Unit = {},
    onExport: () -> Unit = {},
    onBookmarkToggle: () -> Unit = {},
    isBookmarked: Boolean = false,
    onPinToggle: () -> Unit = {},
    isPinned: Boolean = false,
    showPinAction: Boolean = false,
    onMoveToProject: () -> Unit = {},
    showMoveToProject: Boolean = false,
    showShareAction: Boolean = false,
    bookmarksEnabled: Boolean = true,
    // Position offset relative to the anchor; callers feed the long-press point so the menu
    // opens with its left edge under the finger.
    offset: DpOffset = DpOffset.Zero,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        offset = offset,
        shape = MenuShape,
        // A step above the drawer's surfaceContainerLow so the menu reads as a distinct surface
        // floating over it instead of blending into the panel.
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier,
    ) {
        MenuActionItem(
            icon = Icons.Default.Edit,
            label = stringResource(Res.string.rename),
            onClick = {
                onDismiss()
                onRenameRequest()
            },
        )

        if (showPinAction) {
            MenuActionItem(
                icon = if (isPinned) Icons.Default.PushPin else Icons.Outlined.PushPin,
                label = if (isPinned) stringResource(Res.string.unpin) else stringResource(Res.string.pin),
                iconTint = if (isPinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                onClick = {
                    onDismiss()
                    onPinToggle()
                },
            )
        }

        if (bookmarksEnabled) {
            MenuActionItem(
                icon = if (isBookmarked) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                label = if (isBookmarked) stringResource(Res.string.remove_bookmark) else stringResource(Res.string.bookmark),
                iconTint = if (isBookmarked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                onClick = {
                    onDismiss()
                    onBookmarkToggle()
                },
            )
        }

        MenuActionItem(
            icon = Icons.AutoMirrored.Filled.Label,
            label = stringResource(Res.string.tags),
            onClick = {
                onDismiss()
                onTags()
            },
        )

        if (showMoveToProject) {
            MenuActionItem(
                icon = Icons.Default.DriveFileMove,
                label = stringResource(Res.string.move_to_project),
                onClick = {
                    onDismiss()
                    onMoveToProject()
                },
            )
        }

        if (showShareAction) {
            MenuActionItem(
                icon = Icons.Default.Share,
                label = stringResource(Res.string.share),
                onClick = {
                    onDismiss()
                    onShare()
                },
            )
        }

        val duplicateTitle = stringResource(Res.string.copy_of).replace("%1\$s", title)
        MenuActionItem(
            icon = Icons.Default.ContentCopy,
            label = stringResource(Res.string.duplicate),
            onClick = {
                onDismiss()
                onDuplicate(duplicateTitle)
            },
        )

        MenuActionItem(
            icon = Icons.Default.FileDownload,
            label = stringResource(Res.string.export),
            onClick = {
                onDismiss()
                onExport()
            },
        )

        MenuActionItem(
            icon = Icons.Default.Archive,
            label = stringResource(Res.string.archive),
            onClick = {
                onDismiss()
                onArchive()
            },
        )

        MenuActionItem(
            icon = Icons.Default.Delete,
            label = stringResource(Res.string.delete),
            tint = MaterialTheme.colorScheme.error,
            onClick = {
                onDismiss()
                onDeleteRequest()
            },
        )
    }
}

/**
 * Single host for the rename/delete confirmation dialogs driven by [ConversationActionsMenu].
 * Hoisted out of the menu so it survives the menu (and its row) leaving composition; a non-null
 * title shows the corresponding dialog. Lives in this module so it can reach the internal
 * [RenameDialog]/[DeleteConfirmationDialog] and their localized strings.
 */
@Composable
fun ConversationActionDialogs(
    renameTitle: String?,
    deleteTitle: String?,
    onDismissRename: () -> Unit,
    onConfirmRename: (String) -> Unit,
    onDismissDelete: () -> Unit,
    onConfirmDelete: () -> Unit,
) {
    if (renameTitle != null) {
        RenameDialog(
            currentTitle = renameTitle,
            onDismiss = onDismissRename,
            onConfirm = onConfirmRename,
        )
    }

    if (deleteTitle != null) {
        DeleteConfirmationDialog(
            conversationTitle = deleteTitle,
            onDismiss = onDismissDelete,
            onConfirm = onConfirmDelete,
        )
    }
}

@Composable
private fun MenuActionItem(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    iconTint: Color = tint,
) {
    DropdownMenuItem(
        text = { Text(label) },
        onClick = onClick,
        leadingIcon = {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = iconTint,
            )
        },
        colors = MenuDefaults.itemColors(
            textColor = tint,
            leadingIconColor = iconTint,
        ),
    )
}
