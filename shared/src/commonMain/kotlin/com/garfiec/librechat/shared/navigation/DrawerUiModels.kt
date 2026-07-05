package com.garfiec.librechat.shared.navigation

import androidx.compose.runtime.Immutable
import com.garfiec.librechat.core.model.ConversationTag

/**
 * Lightweight snapshot of fields DrawerConversationItem actually renders.
 * Avoids passing the full 28-field Conversation through composition.
 */
@Immutable
data class DrawerConversationDisplayData(
    val conversationId: String,
    val title: String,
    val model: String?,
    val endpoint: String?,
    val relativeTime: String,
    val isActive: Boolean,
    val isFavorite: Boolean,
    val isPinned: Boolean,
    val chatProjectId: String? = null,
    val tags: List<String>,
    val endpointIconUrl: String? = null,
)

/**
 * Inline project-chat accordion state for the drawer's Projects tab: which project is expanded
 * (null = none), its network-loaded chats mapped to the drawer row type, and a loading flag.
 * Single-expand, so only the open project's chats are held.
 */
@Immutable
data class InlineProjectChatsState(
    val expandedProjectId: String? = null,
    val conversations: List<DrawerConversationDisplayData> = emptyList(),
    val isLoading: Boolean = false,
)

/**
 * One signed-in account as the drawer header chip + switcher sheet render it (multi-account,
 * issue #179). Mapped from the persisted roster; ordered active-first, then by recency.
 */
@Immutable
data class AccountUiModel(
    val accountId: String,
    val displayLabel: String,
    val serverHost: String,
    val avatarUrl: String?,
    val isActive: Boolean,
)

/**
 * Combined UI state for the drawer sidebar.
 */
@Immutable
data class DrawerUiState(
    val groupedConversations: List<Pair<String, List<DrawerConversationDisplayData>>> = emptyList(),
    val favoriteConversations: List<DrawerConversationDisplayData> = emptyList(),
    val pinnedConversations: List<DrawerConversationDisplayData> = emptyList(),
    val searchQuery: String = "",
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val hasMore: Boolean = true,
    // Role-permission gates — default permissive.
    val agentsEnabled: Boolean = true,
    val bookmarksEnabled: Boolean = true,
    val skillsEnabled: Boolean = true,
    // User-defined tags (excluding the favorites tag) for the action menu's tag picker.
    val availableTags: List<ConversationTag> = emptyList(),
    // Config-driven: whether server-side shared links are enabled (gates the menu's Share action).
    val sharedLinksEnabled: Boolean = false,
    // Version-gated (v0.8.7+): whether the pin/unpin action is offered. Older servers lack
    // POST /api/convos/pin, so the action is hidden there.
    val pinEnabled: Boolean = false,
    // Version-gated (v0.8.7+): whether the move-to-project action is offered.
    val projectsEnabled: Boolean = false,
)
