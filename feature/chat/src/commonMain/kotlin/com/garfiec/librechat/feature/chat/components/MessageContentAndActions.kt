package com.garfiec.librechat.feature.chat.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.unit.dp
import com.garfiec.librechat.core.model.Message
import com.garfiec.librechat.core.ui.theme.isSurfaceDark
import com.garfiec.librechat.feature.chat.resources.*
import com.garfiec.librechat.feature.chat.resources.Res
import org.jetbrains.compose.resources.stringResource

/**
 * Shared action buttons row for message bubbles (copy, edit, regenerate, feedback, read aloud, fork).
 * Used by both Android and iOS MessageBubble implementations.
 */
@Composable
internal fun ActionButtons(
    isUser: Boolean,
    onFeedback: ((String?) -> Unit)?,
    currentFeedback: String?,
    onShowFeedbackDialog: () -> Unit,
    onCopy: (() -> Unit)?,
    onEdit: (() -> Unit)?,
    onRegenerate: (() -> Unit)?,
    onReadAloud: (() -> Unit)?,
    isReading: Boolean,
    onFork: (() -> Unit)?,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        // Feedback buttons (AI messages only)
        if (!isUser && onFeedback != null) {
            IconButton(
                onClick = { onFeedback(if (currentFeedback == "thumbsUp") null else "thumbsUp") },
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    imageVector = if (currentFeedback == "thumbsUp") Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp,
                    contentDescription = stringResource(Res.string.cd_thumbs_up),
                    modifier = Modifier.size(18.dp),
                    tint = if (currentFeedback == "thumbsUp") {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            IconButton(
                onClick = {
                    if (currentFeedback == "thumbsDown") onFeedback(null) else onShowFeedbackDialog()
                },
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    imageVector = if (currentFeedback == "thumbsDown") Icons.Filled.ThumbDown else Icons.Outlined.ThumbDown,
                    contentDescription = stringResource(Res.string.cd_thumbs_down),
                    modifier = Modifier.size(18.dp),
                    tint = if (currentFeedback == "thumbsDown") {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }

        if (onCopy != null) {
            IconButton(onClick = onCopy, modifier = Modifier.size(40.dp)) {
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = stringResource(Res.string.cd_copy_message),
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (onEdit != null) {
            IconButton(onClick = onEdit, modifier = Modifier.size(40.dp)) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = stringResource(Res.string.cd_edit_message),
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (!isUser && onRegenerate != null) {
            IconButton(onClick = onRegenerate, modifier = Modifier.size(40.dp)) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = stringResource(Res.string.cd_regenerate_response),
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (onReadAloud != null) {
            IconButton(onClick = onReadAloud, modifier = Modifier.size(40.dp)) {
                Icon(
                    imageVector = if (isReading) Icons.Default.Stop else Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = if (isReading) {
                        stringResource(Res.string.cd_stop_reading)
                    } else {
                        stringResource(Res.string.cd_read_aloud)
                    },
                    modifier = Modifier.size(18.dp),
                    tint = if (isReading) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }

        if (onFork != null) {
            IconButton(onClick = onFork, modifier = Modifier.size(40.dp)) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.CallSplit,
                    contentDescription = stringResource(Res.string.cd_fork_conversation),
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Shared message content rendering and action buttons, used by both layout styles on both platforms.
 * Renders: edit mode OR (files + content parts + markdown fallback) + action row with sibling nav.
 */
@Suppress("LongParameterList")
@Composable
internal fun MessageContentAndActions(
    message: Message,
    isUser: Boolean,
    isEditing: Boolean,
    editText: String,
    onEditTextChange: ((String) -> Unit)?,
    onEditSaveAndSubmit: (() -> Unit)?,
    onEditSaveOnly: (() -> Unit)?,
    onEditCancel: (() -> Unit)?,
    baseUrl: String,
    fontSizeMultiplier: Float,
    useKatex: Boolean,
    showImageDescriptions: Boolean,
    searchQuery: String?,
    isSearchMatch: Boolean,
    isCurrentSearchMatch: Boolean,
    searchFocusedOccurrence: Int,
    onFocusedOccurrencePosition: ((LayoutCoordinates, Rect) -> Unit)?,
    showActions: Boolean,
    siblingIndex: Int,
    siblingCount: Int,
    onSiblingNavigation: ((Int) -> Unit)?,
    onEdit: (() -> Unit)?,
    onRegenerate: (() -> Unit)?,
    onCopy: (() -> Unit)?,
    onFeedback: ((String?) -> Unit)?,
    onContinue: (() -> Unit)?,
    onReadAloud: (() -> Unit)?,
    onFork: (() -> Unit)?,
    isReading: Boolean,
    currentFeedback: String?,
    onShowFeedbackDialog: () -> Unit,
) {
    if (isEditing && onEditTextChange != null && onEditSaveAndSubmit != null && onEditSaveOnly != null && onEditCancel != null) {
        // Show the attached files above the edit field so it's clear they're
        // retained on save & submit (the resubmit carries message.files).
        val messageFiles = message.files
        if (!messageFiles.isNullOrEmpty()) {
            MessageFiles(
                files = messageFiles,
                baseUrl = baseUrl,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        InlineEditInput(
            text = editText,
            onTextChange = onEditTextChange,
            onSaveAndSubmit = onEditSaveAndSubmit,
            onSaveOnly = onEditSaveOnly,
            onCancel = onEditCancel,
        )
    } else {
        // Single Column root so this branch emits from one source (and the quote chips,
        // files, content, and office previews stack the same as before).
        Column {
            // Verbatim excerpts the user referenced on this turn (v0.8.7), above the user's
            // text. Created on web; mobile displays them (no creation affordance yet).
            val quotes = message.quotes
            if (isUser && !quotes.isNullOrEmpty()) {
                MessageQuotes(
                    quotes = quotes,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }

            // Render attached files above message text (matches web app behavior)
            val messageFiles = message.files
            if (!messageFiles.isNullOrEmpty()) {
                MessageFiles(
                    files = messageFiles,
                    baseUrl = baseUrl,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }

            val contentParts = message.content
            if (!contentParts.isNullOrEmpty()) {
                // Occurrence indices are message-wide (SearchMatchEnumeration): rebase them
                // per part so each part resolves the focused occurrence within itself. The focused
                // message contains the query by definition, so the fast-path bail never triggers —
                // compute each part's starting offset once (not on every recomposition, which would
                // re-parse every part's markdown each animateScrollBy frame).
                val partOffsets = remember(contentParts, searchQuery, isCurrentSearchMatch) {
                    val offsets = IntArray(contentParts.size)
                    if (isCurrentSearchMatch && !searchQuery.isNullOrBlank()) {
                        var acc = 0
                        contentParts.forEachIndexed { i, part ->
                            offsets[i] = acc
                            acc += countPartOccurrences(part, searchQuery)
                        }
                    }
                    offsets
                }
                contentParts.forEachIndexed { index, part ->
                    val focusedInPart = if (isCurrentSearchMatch) searchFocusedOccurrence - partOffsets[index] else -1
                    ContentPartRenderer(
                        part = part,
                        baseUrl = baseUrl,
                        fontSizeMultiplier = fontSizeMultiplier,
                        useKatex = useKatex,
                        attachments = message.attachments.orEmpty(),
                        showImageDescriptions = showImageDescriptions,
                        searchQuery = if (isSearchMatch) searchQuery else null,
                        searchFocusedOccurrence = focusedInPart,
                        onFocusedOccurrencePosition = if (isCurrentSearchMatch) onFocusedOccurrencePosition else null,
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
            } else if (message.text.isNotBlank()) {
                MarkdownContent(
                    text = message.text,
                    fontSizeMultiplier = fontSizeMultiplier,
                    useKatex = useKatex,
                    searchQuery = if (isSearchMatch) searchQuery else null,
                    searchFocusedOccurrence = if (isCurrentSearchMatch) searchFocusedOccurrence else -1,
                    onFocusedOccurrencePosition = if (isCurrentSearchMatch) onFocusedOccurrencePosition else null,
                )
            }

            // Deferred office-doc preview attachments (v0.8.6) on a persisted message
            // render as their own artifact card. Non-office attachments are unaffected.
            OfficePreviewAttachments(
                attachments = message.attachments.orEmpty(),
                isDarkTheme = isSurfaceDark(),
            )
        }
    }

    // Action row: sibling nav + message actions (tap-to-reveal)
    val hasActions = siblingCount > 1 || onEdit != null || onRegenerate != null ||
        onCopy != null || onFeedback != null || onContinue != null ||
        onReadAloud != null || onFork != null
    if (hasActions) {
        AnimatedVisibility(
            visible = showActions,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Column {
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    // Sibling navigator (left side)
                    if (siblingCount > 1 && onSiblingNavigation != null) {
                        SiblingNavigator(
                            siblingIndex = siblingIndex,
                            siblingCount = siblingCount,
                            onNavigate = onSiblingNavigation,
                        )
                    } else {
                        Spacer(modifier = Modifier.width(0.dp))
                    }

                    // Action buttons (right side)
                    ActionButtons(
                        isUser = isUser,
                        onFeedback = onFeedback,
                        currentFeedback = currentFeedback,
                        onShowFeedbackDialog = onShowFeedbackDialog,
                        onCopy = onCopy,
                        onEdit = onEdit,
                        onRegenerate = onRegenerate,
                        onReadAloud = onReadAloud,
                        isReading = isReading,
                        onFork = onFork,
                    )
                }
            }
        }
    }
}
