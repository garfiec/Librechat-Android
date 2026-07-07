package com.garfiec.librechat.feature.chat.screen

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Platform-specific chat screen implementation. */
@Composable
expect fun ChatScreen(
    modifier: Modifier = Modifier,
    conversationId: String? = null,
    /** True when this Chat(id) entry was created as (or restored as) a temporary chat. Seeds the
     *  VM temp-aware so it never persists the server-hidden conversation. See Chat.isTemporary. */
    isTemporaryRoute: Boolean = false,
    initialAgentId: String? = null,
    /** Explicit (endpoint, model) to pre-select on a new chat — set when launched from a
     *  home-screen model shortcut / quick action. Mutually exclusive with [initialAgentId]. */
    initialEndpoint: String? = null,
    initialModel: String? = null,
    onConversationStart: ((conversationId: String, isTemporary: Boolean) -> Unit)? = null,
    onNavigateToConversation: ((String) -> Unit)? = null,
    onOpenDrawer: (() -> Unit)? = null,
    onNavigateToPromptsLibrary: (() -> Unit)? = null,
    onNavigateBack: (() -> Unit)? = null,
    /** Opens the "Show all media" gallery for the current conversation. Null (and the menu item
     *  hidden) on a brand-new chat that has no conversation id yet. */
    onShowAllMedia: (() -> Unit)? = null,
    /** Opens the server-file picker so the user can attach an already-uploaded file by reference. */
    onAttachFromServer: () -> Unit = {},
    /**
     * Deep-link CTA from the user-provided-key error snackbar and the
     * model-selector "Set API Key" CTA on greyed endpoint groups. Tap navigates to
     * Settings → Provider API Keys. When [endpointName] is non-null, the destination
     * screen auto-opens the Set Key bottom-sheet for that endpoint.
     */
    onNavigateToProviderKeys: (endpointName: String?) -> Unit,
)

/** Platform-specific new chat screen. [initialAgentId], when non-null, pre-selects
 *  that agent for the new chat (set when starting from an agent detail/card).
 *  [initialEndpoint]/[initialModel] pre-select a concrete model (home-screen shortcut). */
@Composable
expect fun NewChatScreen(
    onConversationStart: (conversationId: String, isTemporary: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    initialAgentId: String? = null,
    initialEndpoint: String? = null,
    initialModel: String? = null,
    onOpenDrawer: (() -> Unit)? = null,
    onNavigateToPromptsLibrary: (() -> Unit)? = null,
    onAttachFromServer: () -> Unit = {},
    onNavigateToProviderKeys: (endpointName: String?) -> Unit,
)
