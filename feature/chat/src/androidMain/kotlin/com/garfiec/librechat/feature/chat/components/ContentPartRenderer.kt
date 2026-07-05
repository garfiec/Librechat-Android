package com.garfiec.librechat.feature.chat.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import com.garfiec.librechat.core.model.Attachment
import com.garfiec.librechat.core.model.content.MessageContentPart

@Composable
actual fun ContentPartRenderer(
    part: MessageContentPart,
    modifier: Modifier,
    baseUrl: String,
    fontSizeMultiplier: Float,
    useKatex: Boolean,
    attachments: List<Attachment>,
    showImageDescriptions: Boolean,
    searchQuery: String?,
    searchFocusedOccurrence: Int,
    onFocusedOccurrencePosition: ((LayoutCoordinates, Rect) -> Unit)?,
) {
    ContentPartDispatcher(
        part = part,
        modifier = modifier,
        baseUrl = baseUrl,
        fontSizeMultiplier = fontSizeMultiplier,
        useKatex = useKatex,
        attachments = attachments,
        showImageDescriptions = showImageDescriptions,
        searchQuery = searchQuery,
        searchFocusedOccurrence = searchFocusedOccurrence,
        onFocusedOccurrencePosition = onFocusedOccurrencePosition,
    )
}
