package com.garfiec.librechat.feature.chat.components

import androidx.compose.foundation.background
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
import com.garfiec.librechat.core.common.ChatLayoutConstants
import com.garfiec.librechat.core.ui.components.AvatarImage
import com.garfiec.librechat.core.ui.components.endpointIconPainter
import com.garfiec.librechat.core.ui.components.isMonochromeEndpointIcon

// Shared BubbleShape is imported from MessageBubble.kt

/**
 * Dedicated composable for rendering a message that is currently being streamed.
 * Extracted from MessageBubble.kt to avoid merge conflicts with WS4
 * (which modifies the action row in MessageBubble).
 *
 * Shows the live cursor at the end of the streaming content, or the waiting indicator before the
 * first delta. See StreamingCursor.kt.
 */
@Composable
fun StreamingMessageBubble(
    streamingContent: String,
    senderName: String,
    senderIconUrl: String?,
    modifier: Modifier = Modifier,
    fontSizeMultiplier: Float = 1.0f,
    selectedEndpoint: String? = null,
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
            selectedEndpoint = selectedEndpoint,
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
            selectedEndpoint = selectedEndpoint,
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
    selectedEndpoint: String?,
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
                    fallbackIconPainter = if (senderIconUrl == null) endpointIconPainter(selectedEndpoint) else null,
                    tintIcon = if (senderIconUrl == null) isMonochromeEndpointIcon(selectedEndpoint) else false,
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

        // Streaming content with blinking cursor
        val contentStartPadding = if (showAvatars) 36.dp else 0.dp
        Column(
            modifier = Modifier
                .padding(start = contentStartPadding)
                .fillMaxWidth()
                .then(
                    if (showBubbles) {
                        Modifier
                            .background(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = BubbleShape,
                            )
                            .padding(12.dp)
                    } else {
                        Modifier
                    },
                )
                .semantics {
                    liveRegion = LiveRegionMode.Polite
                    contentDescription = if (streamingContent.isNotBlank()) {
                        "Assistant is responding: $streamingContent"
                    } else {
                        "Assistant is generating a response"
                    }
                },
        ) {
            if (streamingContent.isNotBlank()) {
                // Routed through TextContentPart so artifact directives render as cards while the
                // reply streams (#302) — an unclosed artifact shows its source via IncompleteArtifact.
                // It also owns the cursor — a cursor placed here as a sibling lands on a line of its
                // own instead of after the last word.
                TextContentPart(
                    text = streamingContent,
                    fontSizeMultiplier = fontSizeMultiplier,
                    useKatex = useKatex,
                    streaming = true,
                    trailingCursor = true,
                )
            } else {
                StreamingWaitIndicator()
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
    selectedEndpoint: String?,
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
                fallbackIconPainter = if (senderIconUrl == null) endpointIconPainter(selectedEndpoint) else null,
                tintIcon = if (senderIconUrl == null) isMonochromeEndpointIcon(selectedEndpoint) else false,
                size = 28.dp,
            )
            Spacer(modifier = Modifier.width(6.dp))
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .then(
                    if (showBubbles) {
                        Modifier
                            .background(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = BubbleShape,
                            )
                            .padding(12.dp)
                    } else {
                        Modifier.padding(
                            horizontal = 4.dp,
                            vertical = 8.dp,
                        )
                    },
                )
                .semantics {
                    liveRegion = LiveRegionMode.Polite
                    contentDescription = if (streamingContent.isNotBlank()) {
                        "Assistant is responding: $streamingContent"
                    } else {
                        "Assistant is generating a response"
                    }
                },
        ) {
            Text(
                text = senderName,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.SemiBold,
                ),
                color = if (showBubbles) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            Spacer(modifier = Modifier.height(4.dp))
            if (streamingContent.isNotBlank()) {
                // Routed through TextContentPart so artifact directives render as cards while the
                // reply streams (#302) — an unclosed artifact shows its source via IncompleteArtifact.
                // It also owns the cursor — a cursor placed here as a sibling lands on a line of its
                // own instead of after the last word.
                TextContentPart(
                    text = streamingContent,
                    fontSizeMultiplier = fontSizeMultiplier,
                    useKatex = useKatex,
                    streaming = true,
                    trailingCursor = true,
                )
            } else {
                StreamingWaitIndicator()
            }
        }
    }
}
