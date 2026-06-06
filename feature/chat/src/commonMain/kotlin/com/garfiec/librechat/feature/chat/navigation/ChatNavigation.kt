package com.garfiec.librechat.feature.chat.navigation

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import com.garfiec.librechat.feature.chat.prompts.PromptEditorScreen
import com.garfiec.librechat.feature.chat.prompts.PromptsLibraryScreen
import com.garfiec.librechat.feature.chat.screen.ChatScreen
import com.garfiec.librechat.feature.chat.screen.NewChatScreen
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass

@Serializable sealed interface ChatRoute : NavKey

/** [agentId] (when non-null) is the agent to pre-select for the new chat — set when
 *  starting a chat from an agent's detail/marketplace card so the new chat opens on
 *  that agent rather than falling back to last-used/first-agent/first-model. */
@Serializable data class NewChat(val agentId: String? = null) : ChatRoute

@Serializable data class Chat(val conversationId: String? = null) : ChatRoute

@Serializable data object PromptsLibrary : ChatRoute

@Serializable data class PromptEditor(val groupId: String? = null) : ChatRoute

fun EntryProviderScope<NavKey>.chatEntries(
    onNavigate: (NavKey) -> Unit,
    onBack: () -> Unit,
    onNavigateToChat: (String) -> Unit,
    onOpenDrawer: (() -> Unit)? = null,
    /**
     * Deep-link target for the user-provided-key error CTA snackbar and
     * the model-selector "Set API Key" CTA on greyed endpoint groups. The host navigates
     * to Settings → Provider API Keys. When [endpointName] is non-null, the destination
     * screen auto-opens the Set Key bottom-sheet for that endpoint.
     */
    onNavigateToProviderKeys: (endpointName: String?) -> Unit,
) {
    entry<NewChat> { key ->
        NewChatScreen(
            initialAgentId = key.agentId,
            onConversationStart = { conversationId ->
                onNavigateToChat(conversationId)
            },
            onOpenDrawer = onOpenDrawer,
            onNavigateToPromptsLibrary = { onNavigate(PromptsLibrary) },
            onNavigateToProviderKeys = onNavigateToProviderKeys,
        )
    }
    entry<Chat> { key ->
        ChatScreen(
            conversationId = key.conversationId,
            onOpenDrawer = onOpenDrawer,
            onNavigateToPromptsLibrary = { onNavigate(PromptsLibrary) },
            onNavigateBack = onBack,
            onNavigateToConversation = { conversationId -> onNavigateToChat(conversationId) },
            onNavigateToProviderKeys = onNavigateToProviderKeys,
        )
    }
    entry<PromptsLibrary> {
        PromptsLibraryScreen(
            onNavigateBack = onBack,
            onUseInChat = { _ -> onBack() },
            onNavigateToEditor = { groupId ->
                onNavigate(PromptEditor(groupId = groupId))
            },
        )
    }
    entry<PromptEditor> { key ->
        PromptEditorScreen(
            onBack = onBack,
            groupId = key.groupId,
        )
    }
}

val chatSerializersModule = SerializersModule {
    polymorphic(NavKey::class) {
        subclass(NewChat::class, NewChat.serializer())
        subclass(Chat::class, Chat.serializer())
        subclass(PromptsLibrary::class, PromptsLibrary.serializer())
        subclass(PromptEditor::class, PromptEditor.serializer())
    }
}
