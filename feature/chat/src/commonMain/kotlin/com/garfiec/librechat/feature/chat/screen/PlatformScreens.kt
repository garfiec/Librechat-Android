package com.garfiec.librechat.feature.chat.screen

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Platform-specific chat screen implementation. */
@Composable
expect fun ChatScreen(
    modifier: Modifier = Modifier,
    conversationId: String? = null,
    initialAgentId: String? = null,
    onConversationStart: ((String) -> Unit)? = null,
    onNavigateToConversation: ((String) -> Unit)? = null,
    onOpenDrawer: (() -> Unit)? = null,
    onNavigateToPromptsLibrary: (() -> Unit)? = null,
    onNavigateBack: (() -> Unit)? = null,
    /**
     * Deep-link CTA from the user-provided-key error snackbar and the
     * model-selector "Set API Key" CTA on greyed endpoint groups. Tap navigates to
     * Settings → Provider API Keys. When [endpointName] is non-null, the destination
     * screen auto-opens the Set Key bottom-sheet for that endpoint.
     */
    onNavigateToProviderKeys: (endpointName: String?) -> Unit,
)

/** Platform-specific new chat screen. [initialAgentId], when non-null, pre-selects
 *  that agent for the new chat (set when starting from an agent detail/card). */
@Composable
expect fun NewChatScreen(
    onConversationStart: (String) -> Unit,
    modifier: Modifier = Modifier,
    initialAgentId: String? = null,
    onOpenDrawer: (() -> Unit)? = null,
    onNavigateToPromptsLibrary: (() -> Unit)? = null,
    onNavigateToProviderKeys: (endpointName: String?) -> Unit,
)
