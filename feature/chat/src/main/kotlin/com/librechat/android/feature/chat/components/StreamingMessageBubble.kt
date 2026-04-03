package com.librechat.android.feature.chat.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.librechat.android.core.common.ChatLayoutConstants
import com.librechat.android.core.ui.components.AvatarImage

/**
 * Dedicated composable for rendering a message that is currently being streamed.
 * Extracted from MessageBubble.kt to avoid merge conflicts with WS4
 * (which modifies the action row in MessageBubble).
 *
 * Shows a blinking cursor at the end of the streaming content, or a standalone
 * blinking cursor when no content has arrived yet.
 */
@Composable
fun StreamingMessageBubble(
    streamingContent: String,
    senderName: String,
    senderIconUrl: String?,
    modifier: Modifier = Modifier,
    fontSizeMultiplier: Float = 1.0f,
    @DrawableRes endpointIconRes: Int? = null,
    tintEndpointIcon: Boolean = false,
    chatLayoutStyle: String = ChatLayoutConstants.THREAD,
    showAvatars: Boolean = true,
    showBubbles: Boolean = false,
    useKatex: Boolean = false,
) {
    if (chatLayoutStyle == ChatLayoutConstants.TWO_SIDED) {
        TwoSidedStreamingBubble(
            streamingContent = streamingContent,
            senderName = senderName,
            senderIconUrl = senderIconUrl,
            fontSizeMultiplier = fontSizeMultiplier,
            endpointIconRes = endpointIconRes,
            tintEndpointIcon = tintEndpointIcon,
            showAvatars = showAvatars,
            showBubbles = showBubbles,
            useKatex = useKatex,
            modifier = modifier,
        )
    } else {
        ThreadStreamingBubble(
            streamingContent = streamingContent,
            senderName = senderName,
            senderIconUrl = senderIconUrl,
            fontSizeMultiplier = fontSizeMultiplier,
            endpointIconRes = endpointIconRes,
            tintEndpointIcon = tintEndpointIcon,
            showAvatars = showAvatars,
            showBubbles = showBubbles,
            useKatex = useKatex,
            modifier = modifier,
        )
    }
}

@Composable
private fun ThreadStreamingBubble(
    streamingContent: String,
    senderName: String,
    senderIconUrl: String?,
    fontSizeMultiplier: Float,
    @DrawableRes endpointIconRes: Int?,
    tintEndpointIcon: Boolean,
    showAvatars: Boolean,
    showBubbles: Boolean,
    useKatex: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
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
                    imageUrl = senderIconUrl,
                    fallbackText = senderName,
                    fallbackIconRes = if (senderIconUrl == null) endpointIconRes else null,
                    tintIcon = if (senderIconUrl == null) tintEndpointIcon else false,
                    size = 28.dp,
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(
                text = senderName,
                style = MaterialTheme.typography.titleSmall.copy(
                    fontWeight = FontWeight.SemiBold,
                ),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        val contentStartPadding = if (showAvatars && !showBubbles) 36.dp else 0.dp
        Column(
            modifier = Modifier
                .padding(start = contentStartPadding)
                .fillMaxWidth()
                .semantics {
                    liveRegion = LiveRegionMode.Polite
                    contentDescription = "Assistant is generating a response"
                },
        ) {
            if (streamingContent.isNotBlank()) {
                MarkdownContent(text = streamingContent, fontSizeMultiplier = fontSizeMultiplier, useKatex = useKatex)
                StreamingIndicator()
            } else {
                StreamingIndicator()
            }
        }
    }
}

@Composable
private fun TwoSidedStreamingBubble(
    streamingContent: String,
    senderName: String,
    senderIconUrl: String?,
    fontSizeMultiplier: Float,
    @DrawableRes endpointIconRes: Int?,
    tintEndpointIcon: Boolean,
    showAvatars: Boolean,
    showBubbles: Boolean,
    useKatex: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                horizontal = 8.dp,
                vertical = 4.dp,
            ),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.Top,
    ) {
        // Agent avatar on left
        if (showAvatars) {
            AvatarImage(
                imageUrl = senderIconUrl,
                fallbackText = senderName,
                fallbackIconRes = if (senderIconUrl == null) endpointIconRes else null,
                tintIcon = if (senderIconUrl == null) tintEndpointIcon else false,
                size = 28.dp,
            )
            Spacer(modifier = Modifier.width(6.dp))
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(
                    horizontal = 4.dp,
                    vertical = 8.dp,
                )
                .semantics {
                    liveRegion = LiveRegionMode.Polite
                    contentDescription = "Assistant is generating a response"
                },
        ) {
            Text(
                text = senderName,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.SemiBold,
                ),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(4.dp))
            if (streamingContent.isNotBlank()) {
                MarkdownContent(text = streamingContent, fontSizeMultiplier = fontSizeMultiplier, useKatex = useKatex)
                StreamingIndicator()
            } else {
                StreamingIndicator()
            }
        }
    }
}
