package com.garfiec.librechat.feature.chat.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import com.garfiec.librechat.core.common.ChatLayoutConstants
import com.garfiec.librechat.core.model.Attachment
import com.garfiec.librechat.core.model.Message
import com.garfiec.librechat.core.model.content.MessageContentPart

/** Platform-specific message bubble. */
@Composable
expect fun MessageBubble(
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
    onEditTextChange: ((String) -> Unit)? = null,
    onEditSaveAndSubmit: (() -> Unit)? = null,
    onEditSaveOnly: (() -> Unit)? = null,
    onEditCancel: (() -> Unit)? = null,
    userAvatarUrl: String? = null,
    userName: String? = null,
    selectedEndpoint: String? = null,
    showImageDescriptions: Boolean = true,
    showActionsInitially: Boolean = false,
    searchQuery: String? = null,
    isSearchMatch: Boolean = false,
    isCurrentSearchMatch: Boolean = false,
    searchFocusedOccurrence: Int = -1,
    onFocusedOccurrencePosition: ((LayoutCoordinates, Rect) -> Unit)? = null,
    useKatex: Boolean = false,
    chatLayoutStyle: String = ChatLayoutConstants.THREAD,
    showAvatars: Boolean = true,
    showBubbles: Boolean = false,
)

/** Platform-specific content part rendering. */
@Composable
expect fun ContentPartRenderer(
    part: MessageContentPart,
    modifier: Modifier = Modifier,
    baseUrl: String = "",
    fontSizeMultiplier: Float = 1.0f,
    useKatex: Boolean = false,
    attachments: List<Attachment> = emptyList(),
    showImageDescriptions: Boolean = true,
    searchQuery: String? = null,
    searchFocusedOccurrence: Int = -1,
    onFocusedOccurrencePosition: ((LayoutCoordinates, Rect) -> Unit)? = null,
)

/** Platform-specific markdown content rendering. */
@Composable
expect fun MarkdownContent(
    text: String,
    modifier: Modifier = Modifier,
    fontSizeMultiplier: Float = 1.0f,
    useKatex: Boolean = false,
    searchQuery: String? = null,
    searchFocusedOccurrence: Int = -1,
    onFocusedOccurrencePosition: ((LayoutCoordinates, Rect) -> Unit)? = null,
    immediate: Boolean = false,
    streaming: Boolean = false,
)
