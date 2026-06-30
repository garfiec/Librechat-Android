package com.garfiec.librechat.feature.conversations.navigation

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import com.garfiec.librechat.feature.conversations.screen.ArchivedConversationsScreen
import com.garfiec.librechat.feature.conversations.screen.ConversationListScreen
import com.garfiec.librechat.feature.conversations.screen.ProjectChatsScreen
import com.garfiec.librechat.feature.conversations.screen.ProjectsScreen
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass

@Serializable sealed interface ConversationsRoute : NavKey

@Serializable data object Conversations : ConversationsRoute

@Serializable data object ArchivedConversations : ConversationsRoute

/** Browse-all-folders index (v0.8.7). */
@Serializable data object Projects : ConversationsRoute

/** Per-project filtered conversation list ("Show all" / folder tap) — v0.8.7. */
@Serializable data class ProjectChats(
    val projectId: String,
    val projectName: String,
) : ConversationsRoute

fun EntryProviderScope<NavKey>.conversationsEntries(
    onConversationClick: (String) -> Unit,
    onNavigateToArchive: () -> Unit = {},
    onNavigateToProject: (projectId: String, projectName: String) -> Unit = { _, _ -> },
    onBack: () -> Unit = {},
) {
    entry<Conversations> {
        ConversationListScreen(
            onConversationClick = onConversationClick,
            onNavigateToArchive = onNavigateToArchive,
        )
    }
    entry<ArchivedConversations> {
        ArchivedConversationsScreen(
            onNavigateBack = onBack,
        )
    }
    entry<Projects> {
        ProjectsScreen(
            onProjectClick = onNavigateToProject,
            onNavigateBack = onBack,
        )
    }
    entry<ProjectChats> { route ->
        ProjectChatsScreen(
            projectId = route.projectId,
            projectName = route.projectName,
            onConversationClick = onConversationClick,
            onNavigateBack = onBack,
        )
    }
}

val conversationsSerializersModule = SerializersModule {
    polymorphic(NavKey::class) {
        subclass(Conversations::class, Conversations.serializer())
        subclass(ArchivedConversations::class, ArchivedConversations.serializer())
        subclass(Projects::class, Projects.serializer())
        subclass(ProjectChats::class, ProjectChats.serializer())
    }
}
