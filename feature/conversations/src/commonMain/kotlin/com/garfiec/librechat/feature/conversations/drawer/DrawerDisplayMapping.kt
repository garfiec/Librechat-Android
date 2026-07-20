package com.garfiec.librechat.feature.conversations.drawer

import com.garfiec.librechat.core.model.Conversation
import com.garfiec.librechat.core.model.EndpointConfig
import com.garfiec.librechat.core.model.SAVED_TAG
import com.garfiec.librechat.core.model.resolveEndpointIconUrl

/**
 * The drawer's conversation sections, already mapped to row data.
 *
 * Derived upstream of the search query (see [DrawerViewModel.displayConversations]) so a keystroke
 * re-assembles `DrawerUiState` from these rows instead of re-running the mapping — the grouped list
 * in particular is debounced, so it is usually unchanged between keystrokes.
 */
internal data class DrawerDisplaySnapshot(
    val grouped: List<Pair<String, List<DrawerConversationDisplayData>>> = emptyList(),
    val favorites: List<DrawerConversationDisplayData> = emptyList(),
    val pinned: List<DrawerConversationDisplayData> = emptyList(),
)

internal fun Conversation.toDrawerDisplayData(
    activeConversationId: String?,
    endpointConfigs: Map<String, EndpointConfig>,
): DrawerConversationDisplayData {
    val convId = conversationId ?: ""
    return DrawerConversationDisplayData(
        conversationId = convId,
        title = title ?: "New Chat",
        model = model,
        endpoint = endpoint,
        updatedAt = updatedAt,
        isActive = convId == activeConversationId,
        isFavorite = SAVED_TAG in tags,
        isPinned = pinned == true,
        chatProjectId = chatProjectId,
        tags = tags,
        endpointIconUrl = resolveEndpointIconUrl(endpointConfigs),
    )
}
