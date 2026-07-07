package com.garfiec.librechat.feature.chat.screen

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Thin wrapper that shows ChatScreen with no conversationId.
 * When a conversation starts via streaming, the [onConversationStart]
 * callback is invoked with the new conversationId, allowing navigation
 * to the full chat route.
 */
@Composable
actual fun NewChatScreen(
    onConversationStart: (conversationId: String, isTemporary: Boolean) -> Unit,
    modifier: Modifier,
    initialAgentId: String?,
    initialEndpoint: String?,
    initialModel: String?,
    onOpenDrawer: (() -> Unit)?,
    onNavigateToPromptsLibrary: (() -> Unit)?,
    onAttachFromServer: () -> Unit,
    onNavigateToProviderKeys: (endpointName: String?) -> Unit,
) {
    ChatScreen(
        modifier = modifier,
        initialAgentId = initialAgentId,
        initialEndpoint = initialEndpoint,
        initialModel = initialModel,
        onConversationStart = onConversationStart,
        onOpenDrawer = onOpenDrawer,
        onNavigateToPromptsLibrary = onNavigateToPromptsLibrary,
        onNavigateToProviderKeys = onNavigateToProviderKeys,
        onAttachFromServer = onAttachFromServer,
    )
}
