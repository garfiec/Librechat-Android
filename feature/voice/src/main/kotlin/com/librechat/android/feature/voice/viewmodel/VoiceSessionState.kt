package com.librechat.android.feature.voice.viewmodel

import androidx.compose.runtime.Immutable
import com.librechat.android.core.model.Conversation
import com.librechat.android.core.model.UserFavorite

enum class VoicePhase {
    IDLE,
    LISTENING,
    TRANSCRIBING,
    THINKING,
    SPEAKING,
}

@Immutable
data class VoiceSessionState(
    val phase: VoicePhase = VoicePhase.IDLE,
    /** False until [VoiceSessionViewModel.initializeSession] finishes loading an existing conversation. */
    val isSessionReady: Boolean = false,
    val conversationId: String? = null,
    val selectedEndpoint: String = "agents",
    val selectedModel: String? = null,
    val selectedAgentId: String? = null,
    val lastParentMessageId: String? = null,
    val lastUserText: String = "",
    val lastAssistantText: String = "",
    val streamingText: String = "",
    val error: String? = null,
    val turnCount: Int = 0,
    val isLoadingHome: Boolean = false,
    val recentConversations: List<Conversation> = emptyList(),
    val favoriteModels: List<UserFavorite> = emptyList(),
)
