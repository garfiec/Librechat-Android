package com.librechat.android.feature.chat.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.librechat.android.core.common.ChatLayoutConstants
import com.librechat.android.core.model.Message
import com.librechat.android.core.ui.components.AvatarImage
import com.librechat.android.feature.chat.R
import kotlinx.coroutines.delay

internal val BubbleShape = RoundedCornerShape(16.dp)

private const val ACTION_AUTO_HIDE_MILLIS = 30_000L

@Composable
fun MessageBubble(
    message: Message,
    modifier: Modifier = Modifier,
    siblingIndex: Int = 0,
    siblingCount: Int = 1,
    onSiblingNavigation: ((Int) -> Unit)? = null,
    onEdit: (() -> Unit)? = null,
    onRegenerate: (() -> Unit)? = null,
    onCopy: (() -> Unit)? = null,
    onFeedback: ((String?) -> Unit)? = null,
    onContinue: (() -> Unit)? = null,
    onReadAloud: (() -> Unit)? = null,
    onFork: (() -> Unit)? = null,
    baseUrl: String = "",
    fontSizeMultiplier: Float = 1.0f,
    isReading: Boolean = false,
    currentFeedback: String? = null,
    isEditing: Boolean = false,
    editText: String = "",
    onEditTextChanged: ((String) -> Unit)? = null,
    onEditSaveAndSubmit: (() -> Unit)? = null,
    onEditSaveOnly: (() -> Unit)? = null,
    onEditCancel: (() -> Unit)? = null,
    userAvatarUrl: String? = null,
    userName: String? = null,
    @DrawableRes endpointIconRes: Int? = null,
    tintEndpointIcon: Boolean = false,
    showImageDescriptions: Boolean = true,
    showActionsInitially: Boolean = false,
    searchQuery: String? = null,
    isSearchMatch: Boolean = false,
    isCurrentSearchMatch: Boolean = false,
    searchFocusedOccurrence: Int = -1,
    onFocusedOccurrencePositioned: ((LayoutCoordinates) -> Unit)? = null,
    useKatex: Boolean = false,
    chatLayoutStyle: String = ChatLayoutConstants.THREAD,
    showAvatars: Boolean = true,
    showBubbles: Boolean = false,
) {
    if (chatLayoutStyle == ChatLayoutConstants.TWO_SIDED) {
        TwoSidedMessageBubble(
            message = message,
            modifier = modifier,
            siblingIndex = siblingIndex,
            siblingCount = siblingCount,
            onSiblingNavigation = onSiblingNavigation,
            onEdit = onEdit,
            onRegenerate = onRegenerate,
            onCopy = onCopy,
            onFeedback = onFeedback,
            onContinue = onContinue,
            onReadAloud = onReadAloud,
            onFork = onFork,
            baseUrl = baseUrl,
            fontSizeMultiplier = fontSizeMultiplier,
            useKatex = useKatex,
            isReading = isReading,
            currentFeedback = currentFeedback,
            isEditing = isEditing,
            editText = editText,
            onEditTextChanged = onEditTextChanged,
            onEditSaveAndSubmit = onEditSaveAndSubmit,
            onEditSaveOnly = onEditSaveOnly,
            onEditCancel = onEditCancel,
            userAvatarUrl = userAvatarUrl,
            userName = userName,
            endpointIconRes = endpointIconRes,
            tintEndpointIcon = tintEndpointIcon,
            showImageDescriptions = showImageDescriptions,
            showActionsInitially = showActionsInitially,
            searchQuery = searchQuery,
            isSearchMatch = isSearchMatch,
            isCurrentSearchMatch = isCurrentSearchMatch,
            searchFocusedOccurrence = searchFocusedOccurrence,
            onFocusedOccurrencePositioned = onFocusedOccurrencePositioned,
            showAvatars = showAvatars,
            showBubbles = showBubbles,
        )
    } else {
        ThreadMessageBubble(
            message = message,
            modifier = modifier,
            siblingIndex = siblingIndex,
            siblingCount = siblingCount,
            onSiblingNavigation = onSiblingNavigation,
            onEdit = onEdit,
            onRegenerate = onRegenerate,
            onCopy = onCopy,
            onFeedback = onFeedback,
            onContinue = onContinue,
            onReadAloud = onReadAloud,
            onFork = onFork,
            baseUrl = baseUrl,
            fontSizeMultiplier = fontSizeMultiplier,
            useKatex = useKatex,
            isReading = isReading,
            currentFeedback = currentFeedback,
            isEditing = isEditing,
            editText = editText,
            onEditTextChanged = onEditTextChanged,
            onEditSaveAndSubmit = onEditSaveAndSubmit,
            onEditSaveOnly = onEditSaveOnly,
            onEditCancel = onEditCancel,
            userAvatarUrl = userAvatarUrl,
            userName = userName,
            endpointIconRes = endpointIconRes,
            tintEndpointIcon = tintEndpointIcon,
            showImageDescriptions = showImageDescriptions,
            showActionsInitially = showActionsInitially,
            searchQuery = searchQuery,
            isSearchMatch = isSearchMatch,
            isCurrentSearchMatch = isCurrentSearchMatch,
            searchFocusedOccurrence = searchFocusedOccurrence,
            onFocusedOccurrencePositioned = onFocusedOccurrencePositioned,
            showAvatars = showAvatars,
            showBubbles = showBubbles,
        )
    }
}

/**
 * Original thread-style layout: avatar on left, name + time, content below.
 */
@Composable
private fun ThreadMessageBubble(
    message: Message,
    modifier: Modifier = Modifier,
    siblingIndex: Int = 0,
    siblingCount: Int = 1,
    onSiblingNavigation: ((Int) -> Unit)? = null,
    onEdit: (() -> Unit)? = null,
    onRegenerate: (() -> Unit)? = null,
    onCopy: (() -> Unit)? = null,
    onFeedback: ((String?) -> Unit)? = null,
    onContinue: (() -> Unit)? = null,
    onReadAloud: (() -> Unit)? = null,
    onFork: (() -> Unit)? = null,
    baseUrl: String = "",
    fontSizeMultiplier: Float = 1.0f,
    useKatex: Boolean = false,
    isReading: Boolean = false,
    currentFeedback: String? = null,
    isEditing: Boolean = false,
    editText: String = "",
    onEditTextChanged: ((String) -> Unit)? = null,
    onEditSaveAndSubmit: (() -> Unit)? = null,
    onEditSaveOnly: (() -> Unit)? = null,
    onEditCancel: (() -> Unit)? = null,
    userAvatarUrl: String? = null,
    userName: String? = null,
    @DrawableRes endpointIconRes: Int? = null,
    tintEndpointIcon: Boolean = false,
    showImageDescriptions: Boolean = true,
    showActionsInitially: Boolean = false,
    searchQuery: String? = null,
    isSearchMatch: Boolean = false,
    isCurrentSearchMatch: Boolean = false,
    searchFocusedOccurrence: Int = -1,
    onFocusedOccurrencePositioned: ((LayoutCoordinates) -> Unit)? = null,
    showAvatars: Boolean = true,
    showBubbles: Boolean = false,
) {
    val isUser = message.isCreatedByUser
    var showActions by remember(message.messageId) { mutableStateOf(showActionsInitially) }
    var showFeedbackDialog by remember { mutableStateOf(false) }

    if (showFeedbackDialog && onFeedback != null) {
        FeedbackCommentDialog(
            onSubmit = { comment ->
                showFeedbackDialog = false
                onFeedback("thumbsDown")
            },
            onDismiss = { showFeedbackDialog = false },
        )
    }

    // Auto-hide actions after timeout
    LaunchedEffect(showActions) {
        if (showActions) {
            delay(ACTION_AUTO_HIDE_MILLIS)
            showActions = false
        }
    }

    // Determine background color for search state
    val searchBackground = when {
        isCurrentSearchMatch -> SearchHighlightOrange.copy(alpha = 0.18f)
        isSearchMatch -> SearchHighlightYellow.copy(alpha = 0.12f)
        else -> null
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (searchBackground != null) {
                    Modifier.background(
                        color = searchBackground,
                        shape = RoundedCornerShape(8.dp),
                    )
                } else {
                    Modifier
                },
            )
            .clickable(
                interactionSource = null,
                indication = null,
            ) {
                showActions = !showActions
            }
            .padding(
                horizontal = 16.dp,
                vertical = 8.dp,
            ),
    ) {
        // Sender row with avatar
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showAvatars) {
                AvatarImage(
                    imageUrl = if (isUser) userAvatarUrl else message.iconURL,
                    fallbackText = if (isUser) {
                        userName ?: stringResource(R.string.sender_you)
                    } else {
                        message.sender ?: stringResource(R.string.sender_assistant)
                    },
                    fallbackIconRes = if (!isUser && message.iconURL == null) endpointIconRes else null,
                    showPersonIcon = isUser && userAvatarUrl == null,
                    tintIcon = if (!isUser && message.iconURL == null) tintEndpointIcon else false,
                    size = 28.dp,
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(
                text = if (isUser) {
                    userName ?: stringResource(R.string.sender_you)
                } else {
                    message.sender ?: stringResource(R.string.sender_assistant)
                },
                style = MaterialTheme.typography.titleSmall.copy(
                    fontWeight = FontWeight.SemiBold,
                ),
                color = MaterialTheme.colorScheme.onSurface,
            )

            // Timestamp
            val timestamp = message.createdAt
            if (timestamp != null) {
                Spacer(modifier = Modifier.width(8.dp))
                MessageTimestamp(isoTimestamp = timestamp)
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        val contentStartPadding = if (showAvatars && !showBubbles) 36.dp else 0.dp

        Column(
            modifier = Modifier
                .padding(start = contentStartPadding),
        ) {
            MessageContentAndActions(
                message = message,
                isUser = isUser,
                isEditing = isEditing,
                editText = editText,
                onEditTextChanged = onEditTextChanged,
                onEditSaveAndSubmit = onEditSaveAndSubmit,
                onEditSaveOnly = onEditSaveOnly,
                onEditCancel = onEditCancel,
                baseUrl = baseUrl,
                fontSizeMultiplier = fontSizeMultiplier,
                useKatex = useKatex,
                showImageDescriptions = showImageDescriptions,
                searchQuery = searchQuery,
                isSearchMatch = isSearchMatch,
                isCurrentSearchMatch = isCurrentSearchMatch,
                searchFocusedOccurrence = searchFocusedOccurrence,
                onFocusedOccurrencePositioned = onFocusedOccurrencePositioned,
                showActions = showActions,
                siblingIndex = siblingIndex,
                siblingCount = siblingCount,
                onSiblingNavigation = onSiblingNavigation,
                onEdit = onEdit,
                onRegenerate = onRegenerate,
                onCopy = onCopy,
                onFeedback = onFeedback,
                onContinue = onContinue,
                onReadAloud = onReadAloud,
                onFork = onFork,
                isReading = isReading,
                currentFeedback = currentFeedback,
                onShowFeedbackDialog = { showFeedbackDialog = true },
            )
        }
    }
}

/**
 * Two-sided chat layout: user messages on right, agent on left, with bubble backgrounds.
 */
@Composable
private fun TwoSidedMessageBubble(
    message: Message,
    modifier: Modifier = Modifier,
    siblingIndex: Int = 0,
    siblingCount: Int = 1,
    onSiblingNavigation: ((Int) -> Unit)? = null,
    onEdit: (() -> Unit)? = null,
    onRegenerate: (() -> Unit)? = null,
    onCopy: (() -> Unit)? = null,
    onFeedback: ((String?) -> Unit)? = null,
    onContinue: (() -> Unit)? = null,
    onReadAloud: (() -> Unit)? = null,
    onFork: (() -> Unit)? = null,
    baseUrl: String = "",
    fontSizeMultiplier: Float = 1.0f,
    useKatex: Boolean = false,
    isReading: Boolean = false,
    currentFeedback: String? = null,
    isEditing: Boolean = false,
    editText: String = "",
    onEditTextChanged: ((String) -> Unit)? = null,
    onEditSaveAndSubmit: (() -> Unit)? = null,
    onEditSaveOnly: (() -> Unit)? = null,
    onEditCancel: (() -> Unit)? = null,
    userAvatarUrl: String? = null,
    userName: String? = null,
    @DrawableRes endpointIconRes: Int? = null,
    tintEndpointIcon: Boolean = false,
    showImageDescriptions: Boolean = true,
    showActionsInitially: Boolean = false,
    searchQuery: String? = null,
    isSearchMatch: Boolean = false,
    isCurrentSearchMatch: Boolean = false,
    searchFocusedOccurrence: Int = -1,
    onFocusedOccurrencePositioned: ((LayoutCoordinates) -> Unit)? = null,
    showAvatars: Boolean = true,
    showBubbles: Boolean = false,
) {
    val isUser = message.isCreatedByUser
    var showActions by remember(message.messageId) { mutableStateOf(showActionsInitially) }
    var showFeedbackDialog by remember { mutableStateOf(false) }

    if (showFeedbackDialog && onFeedback != null) {
        FeedbackCommentDialog(
            onSubmit = { comment ->
                showFeedbackDialog = false
                onFeedback("thumbsDown")
            },
            onDismiss = { showFeedbackDialog = false },
        )
    }

    // Auto-hide actions after timeout
    LaunchedEffect(showActions) {
        if (showActions) {
            delay(ACTION_AUTO_HIDE_MILLIS)
            showActions = false
        }
    }

    // Determine background color for search state
    val searchBackground = when {
        isCurrentSearchMatch -> SearchHighlightOrange.copy(alpha = 0.18f)
        isSearchMatch -> SearchHighlightYellow.copy(alpha = 0.12f)
        else -> null
    }

    val textColor = MaterialTheme.colorScheme.onSurface

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = null,
                indication = null,
            ) {
                showActions = !showActions
            }
            .padding(
                horizontal = 8.dp,
                vertical = 4.dp,
            ),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top,
    ) {
        // Agent avatar on left
        if (!isUser && showAvatars) {
            AvatarImage(
                imageUrl = message.iconURL,
                fallbackText = message.sender ?: stringResource(R.string.sender_assistant),
                fallbackIconRes = if (message.iconURL == null) endpointIconRes else null,
                tintIcon = if (message.iconURL == null) tintEndpointIcon else false,
                size = 28.dp,
            )
            Spacer(modifier = Modifier.width(6.dp))
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .then(
                    if (searchBackground != null) {
                        Modifier
                            .background(
                                color = searchBackground,
                                shape = BubbleShape,
                            )
                            .padding(12.dp)
                    } else {
                        Modifier.padding(
                            horizontal = 4.dp,
                            vertical = 8.dp,
                        )
                    },
                ),
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
        ) {
            // Name + timestamp inside bubble
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (isUser) {
                        userName ?: stringResource(R.string.sender_you)
                    } else {
                        message.sender ?: stringResource(R.string.sender_assistant)
                    },
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = textColor,
                )
                val timestamp = message.createdAt
                if (timestamp != null) {
                    Spacer(modifier = Modifier.width(6.dp))
                    MessageTimestamp(isoTimestamp = timestamp)
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Message content
            MessageContentAndActions(
                message = message,
                isUser = isUser,
                isEditing = isEditing,
                editText = editText,
                onEditTextChanged = onEditTextChanged,
                onEditSaveAndSubmit = onEditSaveAndSubmit,
                onEditSaveOnly = onEditSaveOnly,
                onEditCancel = onEditCancel,
                baseUrl = baseUrl,
                fontSizeMultiplier = fontSizeMultiplier,
                useKatex = useKatex,
                showImageDescriptions = showImageDescriptions,
                searchQuery = searchQuery,
                isSearchMatch = isSearchMatch,
                isCurrentSearchMatch = isCurrentSearchMatch,
                searchFocusedOccurrence = searchFocusedOccurrence,
                onFocusedOccurrencePositioned = onFocusedOccurrencePositioned,
                showActions = showActions,
                siblingIndex = siblingIndex,
                siblingCount = siblingCount,
                onSiblingNavigation = onSiblingNavigation,
                onEdit = onEdit,
                onRegenerate = onRegenerate,
                onCopy = onCopy,
                onFeedback = onFeedback,
                onContinue = onContinue,
                onReadAloud = onReadAloud,
                onFork = onFork,
                isReading = isReading,
                currentFeedback = currentFeedback,
                onShowFeedbackDialog = { showFeedbackDialog = true },
            )
        }

        // User avatar on right
        if (isUser && showAvatars) {
            Spacer(modifier = Modifier.width(6.dp))
            AvatarImage(
                imageUrl = userAvatarUrl,
                fallbackText = userName ?: stringResource(R.string.sender_you),
                showPersonIcon = userAvatarUrl == null,
                size = 28.dp,
            )
        }
    }
}

/**
 * Shared message content rendering and action buttons, used by both layout styles.
 */
@Composable
private fun MessageContentAndActions(
    message: Message,
    isUser: Boolean,
    isEditing: Boolean,
    editText: String,
    onEditTextChanged: ((String) -> Unit)?,
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
    onFocusedOccurrencePositioned: ((LayoutCoordinates) -> Unit)?,
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
    if (isEditing && onEditTextChanged != null && onEditSaveAndSubmit != null && onEditSaveOnly != null && onEditCancel != null) {
        InlineEditInput(
            text = editText,
            onTextChanged = onEditTextChanged,
            onSaveAndSubmit = onEditSaveAndSubmit,
            onSaveOnly = onEditSaveOnly,
            onCancel = onEditCancel,
        )
    } else {
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
            contentParts.forEachIndexed { partIndex, part ->
                key(partIndex) {
                    ContentPartRenderer(
                        part = part,
                        baseUrl = baseUrl,
                        fontSizeMultiplier = fontSizeMultiplier,
                        useKatex = useKatex,
                        attachments = message.attachments.orEmpty(),
                        showImageDescriptions = showImageDescriptions,
                        searchQuery = if (isSearchMatch) searchQuery else null,
                        searchFocusedOccurrence = if (isCurrentSearchMatch) searchFocusedOccurrence else -1,
                        onFocusedOccurrencePositioned = if (isCurrentSearchMatch) onFocusedOccurrencePositioned else null,
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
            }
        } else if (message.text.isNotBlank()) {
            MarkdownContent(
                text = message.text,
                fontSizeMultiplier = fontSizeMultiplier,
                useKatex = useKatex,
                searchQuery = if (isSearchMatch) searchQuery else null,
                searchFocusedOccurrence = if (isCurrentSearchMatch) searchFocusedOccurrence else -1,
                onFocusedOccurrencePositioned = if (isCurrentSearchMatch) onFocusedOccurrencePositioned else null,
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
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Feedback buttons (AI messages only)
                        if (!isUser && onFeedback != null) {
                            IconButton(
                                onClick = {
                                    onFeedback(
                                        if (currentFeedback == "thumbsUp") null else "thumbsUp",
                                    )
                                },
                                modifier = Modifier.size(40.dp),
                            ) {
                                Icon(
                                    imageVector = if (currentFeedback == "thumbsUp") {
                                        Icons.Filled.ThumbUp
                                    } else {
                                        Icons.Outlined.ThumbUp
                                    },
                                    contentDescription = stringResource(R.string.cd_thumbs_up),
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
                                    if (currentFeedback == "thumbsDown") {
                                        onFeedback(null)
                                    } else {
                                        onShowFeedbackDialog()
                                    }
                                },
                                modifier = Modifier.size(40.dp),
                            ) {
                                Icon(
                                    imageVector = if (currentFeedback == "thumbsDown") {
                                        Icons.Filled.ThumbDown
                                    } else {
                                        Icons.Outlined.ThumbDown
                                    },
                                    contentDescription = stringResource(R.string.cd_thumbs_down),
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
                            IconButton(
                                onClick = onCopy,
                                modifier = Modifier.size(40.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ContentCopy,
                                    contentDescription = stringResource(R.string.cd_copy_message),
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }

                        if (onEdit != null) {
                            IconButton(
                                onClick = onEdit,
                                modifier = Modifier.size(40.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Edit,
                                    contentDescription = stringResource(R.string.cd_edit_message),
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }

                        if (!isUser && onRegenerate != null) {
                            IconButton(
                                onClick = onRegenerate,
                                modifier = Modifier.size(40.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = stringResource(R.string.cd_regenerate_response),
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }

                        // Read aloud button
                        if (onReadAloud != null) {
                            IconButton(
                                onClick = onReadAloud,
                                modifier = Modifier.size(40.dp),
                            ) {
                                Icon(
                                    imageVector = if (isReading) {
                                        Icons.Default.Stop
                                    } else {
                                        Icons.AutoMirrored.Filled.VolumeUp
                                    },
                                    contentDescription = if (isReading) {
                                        stringResource(R.string.cd_stop_reading)
                                    } else {
                                        stringResource(R.string.cd_read_aloud)
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

                        // Fork conversation from this message
                        if (onFork != null) {
                            IconButton(
                                onClick = onFork,
                                modifier = Modifier.size(40.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.CallSplit,
                                    contentDescription = stringResource(R.string.cd_fork_conversation),
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
