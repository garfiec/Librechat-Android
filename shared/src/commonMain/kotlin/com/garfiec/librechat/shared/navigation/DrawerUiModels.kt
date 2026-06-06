package com.garfiec.librechat.shared.navigation

import androidx.compose.runtime.Immutable

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
    val tags: List<String>,
    val endpointIconUrl: String? = null,
)

/**
 * Combined UI state for the drawer sidebar.
 */
@Immutable
data class DrawerUiState(
    val groupedConversations: List<Pair<String, List<DrawerConversationDisplayData>>> = emptyList(),
    val favoriteConversations: List<DrawerConversationDisplayData> = emptyList(),
    val searchQuery: String = "",
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val hasMore: Boolean = true,
    // Role-permission gates — default permissive.
    val agentsEnabled: Boolean = true,
    val bookmarksEnabled: Boolean = true,
    val skillsEnabled: Boolean = true,
)
