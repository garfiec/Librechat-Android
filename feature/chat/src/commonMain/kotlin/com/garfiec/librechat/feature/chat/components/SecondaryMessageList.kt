package com.garfiec.librechat.feature.chat.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.garfiec.librechat.core.common.ChatLayoutConstants
import com.garfiec.librechat.core.model.Attachment
import com.garfiec.librechat.feature.chat.resources.*
import com.garfiec.librechat.feature.chat.resources.Res
import com.garfiec.librechat.feature.chat.util.MessageNode
import com.garfiec.librechat.feature.chat.viewmodel.ActiveToolCall
import org.jetbrains.compose.resources.stringResource

/**
 * Simplified message list for the secondary comparison pane.
 * Does not support editing, forking, sibling navigation, or search highlighting.
 * Just displays messages and streaming content for the comparison model.
 */
@Composable
fun SecondaryMessageList(
    displayMessages: List<MessageNode>,
    isStreaming: Boolean,
    streamingContent: String,
    modifier: Modifier = Modifier,
    activeToolCalls: List<ActiveToolCall> = emptyList(),
    streamingAttachments: List<Attachment> = emptyList(),
    error: String? = null,
    baseUrl: String = "",
    fontSizeMultiplier: Float = 1.0f,
    selectedEndpoint: String? = null,
    streamingSenderName: String = "Assistant",
    showImageDescriptions: Boolean = true,
    chatLayoutStyle: String = ChatLayoutConstants.THREAD,
    showAvatars: Boolean = true,
    showBubbles: Boolean = false,
    useKatex: Boolean = false,
) {
    val listState = rememberLazyListState()
    val totalItemCount = displayMessages.size +
        (if (isStreaming) 1 else 0) +
        (if (isStreaming) activeToolCalls.size else 0)

    // Auto-scroll to bottom during streaming
    LaunchedEffect(streamingContent.length, totalItemCount) {
        if (totalItemCount > 0) {
            listState.scrollToItem(totalItemCount - 1, scrollOffset = Int.MAX_VALUE)
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (error != null) {
            Text(
                text = error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(16.dp),
            )
        } else if (!isStreaming && displayMessages.isEmpty() && streamingContent.isBlank()) {
            Text(
                text = stringResource(Res.string.waiting_for_response),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(16.dp),
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                contentPadding = PaddingValues(top = 8.dp, bottom = 160.dp),
            ) {
                items(
                    items = displayMessages,
                    key = { node -> "secondary_${node.message.messageId}" },
                    contentType = { "message" },
                ) { node ->
                    MessageBubble(
                        message = node.message,
                        siblingIndex = node.siblingIndex,
                        siblingCount = node.siblingCount,
                        onSiblingNavigation = { /* no-op for secondary */ },
                        onEdit = {},
                        onCopy = {},
                        baseUrl = baseUrl,
                        fontSizeMultiplier = fontSizeMultiplier,
                        selectedEndpoint = selectedEndpoint,
                        showImageDescriptions = showImageDescriptions,
                        chatLayoutStyle = chatLayoutStyle,
                        showAvatars = showAvatars,
                        showBubbles = showBubbles,
                        useKatex = useKatex,
                    )
                }

                if (isStreaming) {
                    item(key = "secondary_streaming_message") {
                        StreamingMessageBubble(
                            streamingContent = streamingContent,
                            senderName = streamingSenderName,
                            senderIconUrl = null,
                            fontSizeMultiplier = fontSizeMultiplier,
                            selectedEndpoint = selectedEndpoint,
                            chatLayoutStyle = chatLayoutStyle,
                            showAvatars = showAvatars,
                            showBubbles = showBubbles,
                            useKatex = useKatex,
                        )
                    }

                    if (activeToolCalls.isNotEmpty()) {
                        items(
                            items = activeToolCalls,
                            key = { "secondary_tool_call_${it.id}" },
                            contentType = { "tool_call" },
                        ) { toolCall ->
                            StreamingToolCallCard(
                                toolCall = toolCall,
                                modifier = Modifier.padding(
                                    horizontal = 16.dp,
                                    vertical = 4.dp,
                                ),
                                baseUrl = baseUrl,
                                streamingAttachments = streamingAttachments,
                            )
                        }
                    }
                }
            }
        }
    }
}
