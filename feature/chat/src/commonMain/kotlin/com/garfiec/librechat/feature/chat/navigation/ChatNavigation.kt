package com.garfiec.librechat.feature.chat.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import com.garfiec.librechat.core.data.datastore.ArtifactDisplayMode
import com.garfiec.librechat.core.data.datastore.ArtifactDisplayPrefs
import com.garfiec.librechat.core.data.datastore.SettingsDataStore
import com.garfiec.librechat.feature.chat.components.artifact.Artifact
import com.garfiec.librechat.feature.chat.components.artifact.ArtifactPanel
import com.garfiec.librechat.feature.chat.components.artifact.ArtifactViewerHandoff
import com.garfiec.librechat.feature.chat.components.artifact.LocalOpenArtifact
import com.garfiec.librechat.feature.chat.components.artifact.ProvideAddArtifactToHomeScreen
import com.garfiec.librechat.feature.chat.prompts.PromptEditorScreen
import com.garfiec.librechat.feature.chat.prompts.PromptsLibraryScreen
import com.garfiec.librechat.feature.chat.screen.ArtifactFullscreenScreen
import com.garfiec.librechat.feature.chat.screen.ArtifactShortcutViewerScreen
import com.garfiec.librechat.feature.chat.screen.ChatScreen
import com.garfiec.librechat.feature.chat.screen.ConversationMediaScreen
import com.garfiec.librechat.feature.chat.screen.NewChatScreen
import com.garfiec.librechat.feature.chat.viewmodel.PromptInsertionHandoff
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import org.koin.compose.koinInject

@Serializable sealed interface ChatRoute : NavKey

/** [agentId] (when non-null) is the agent to pre-select for the new chat — set when
 *  starting a chat from an agent's detail/marketplace card so the new chat opens on
 *  that agent rather than falling back to last-used/first-agent/first-model.
 *  [endpoint]/[model] pre-select a concrete model instead (home-screen model shortcut /
 *  quick action); they ride on the route so a payload-carrying NewChat replaces a bare
 *  landing NewChat (dedup-by-value) and re-seeds even when already on the landing. */
@Serializable data class NewChat(
    val agentId: String? = null,
    val endpoint: String? = null,
    val model: String? = null,
) : ChatRoute

/**
 * [isTemporary] marks a conversation the user started as a temporary chat. It rides on the
 * route (not just the in-memory NewChatSelectionHandoff) so it survives process death: a restored
 * Chat(id) entry re-initializes temp-aware and never persists the server-hidden conversation to
 * Room. SECURITY: this is the temp-chat data-at-rest guard for the handed-off / restored Chat VM.
 */
@Serializable data class Chat(val conversationId: String? = null, val isTemporary: Boolean = false) : ChatRoute

@Serializable data object PromptsLibrary : ChatRoute

@Serializable data class PromptEditor(val groupId: String? = null) : ChatRoute

/** Telegram-style "Show all media" gallery for a single conversation. */
@Serializable data class ConversationMedia(val conversationId: String) : ChatRoute

/**
 * Full-screen artifact viewer. Carries only the lightweight identity; the artifact's
 * (potentially large) content is handed off in-memory via [ArtifactViewerHandoff].
 */
@Serializable data class ArtifactFullscreen(val identifier: String, val version: Int) : ChatRoute

/**
 * Viewer for a home-screen-pinned artifact, opened by tapping its launcher icon
 * (`librechat://artifact/{snapshotId}`). Unlike [ArtifactFullscreen] it loads a self-contained Room
 * snapshot by [snapshotId], so it survives cold start / process death and works while logged out.
 */
@Serializable data class ArtifactShortcutViewer(val snapshotId: String) : ChatRoute

fun EntryProviderScope<NavKey>.chatEntries(
    onNavigate: (NavKey) -> Unit,
    onBack: () -> Unit,
    onNavigateToChat: (conversationId: String, isTemporary: Boolean) -> Unit,
    onOpenDrawer: (() -> Unit)? = null,
    /**
     * Deep-link target for the user-provided-key error CTA snackbar and
     * the model-selector "Set API Key" CTA on greyed endpoint groups. The host navigates
     * to Settings → Provider API Keys. When [endpointName] is non-null, the destination
     * screen auto-opens the Set Key bottom-sheet for that endpoint.
     */
    onNavigateToProviderKeys: (endpointName: String?) -> Unit,
    /** Navigates to the server-file picker; wired by the host to `navigator.navigate(FilesPicker)`. */
    onAttachFromServer: () -> Unit,
) {
    entry<NewChat> { key ->
        NewChatScreen(
            initialAgentId = key.agentId,
            initialEndpoint = key.endpoint,
            initialModel = key.model,
            onConversationStart = { conversationId, isTemporary ->
                onNavigateToChat(conversationId, isTemporary)
            },
            onOpenDrawer = onOpenDrawer,
            onNavigateToPromptsLibrary = { onNavigate(PromptsLibrary) },
            onNavigateToProviderKeys = onNavigateToProviderKeys,
            onAttachFromServer = onAttachFromServer,
        )
    }
    entry<Chat> { key ->
        ProvideOpenArtifact(onNavigate) {
            ChatScreen(
                conversationId = key.conversationId,
                // A restored temp Chat(id) entry carries isTemporary=true so the VM re-initializes
                // temp-aware (never persists the server-hidden conversation). See ChatViewModel.init.
                isTemporaryRoute = key.isTemporary,
                onOpenDrawer = onOpenDrawer,
                onNavigateToPromptsLibrary = { onNavigate(PromptsLibrary) },
                onNavigateBack = onBack,
                // Fork/duplicate produce real (non-temp) conversations.
                onNavigateToConversation = { conversationId -> onNavigateToChat(conversationId, false) },
                // Null on a new chat with no id yet, which hides the overflow menu item.
                onShowAllMedia = key.conversationId?.let { id -> { onNavigate(ConversationMedia(id)) } },
                onNavigateToProviderKeys = onNavigateToProviderKeys,
                onAttachFromServer = onAttachFromServer,
            )
        }
    }
    entry<ConversationMedia> { key ->
        ProvideOpenArtifact(onNavigate) {
            ConversationMediaScreen(
                conversationId = key.conversationId,
                onNavigateBack = onBack,
            )
        }
    }
    entry<ArtifactFullscreen> { key ->
        // Provider present so the fullscreen viewer's toolbar can pin the artifact too.
        ProvideAddArtifactToHomeScreen {
            ArtifactFullscreenScreen(
                identifier = key.identifier,
                version = key.version,
                onBack = onBack,
            )
        }
    }
    entry<ArtifactShortcutViewer> { key ->
        // No pin provider here: a launcher-opened artifact can't be re-pinned from its own screen.
        ArtifactShortcutViewerScreen(
            snapshotId = key.snapshotId,
            onBack = onBack,
        )
    }
    entry<PromptsLibrary> {
        // The chat entry is off-composition while the library is up, so the text is staged here and
        // collected when the chat screen re-enters on pop.
        val promptInsertionHandoff = koinInject<PromptInsertionHandoff>()
        PromptsLibraryScreen(
            onNavigateBack = onBack,
            onUseInChat = { text ->
                promptInsertionHandoff.put(text)
                onBack()
            },
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

/**
 * Owns the screen-level artifact presentation and provides [LocalOpenArtifact] so artifacts
 * tapped deep in the message list just fire an event. Honoring the display-mode pref it either
 * pushes the [ArtifactFullscreen] route (payload staged in [ArtifactViewerHandoff]; real nav
 * destination → predictive back) or shows a bottom sheet rendered here at the screen root —
 * not inline in the list, so it survives the tapped item scrolling off-screen.
 */
@Composable
private fun ProvideOpenArtifact(
    onNavigate: (NavKey) -> Unit,
    content: @Composable () -> Unit,
) {
    val handoff = koinInject<ArtifactViewerHandoff>()
    val settingsDataStore = koinInject<SettingsDataStore>()
    // Held as State, not read via `by` at composable scope: the mode only matters at tap time, so
    // reading it inside the lambda keeps a pref change from recomposing the whole content() subtree.
    val displayPrefs = settingsDataStore.artifactDisplayPrefs
        .collectAsStateWithLifecycle(ArtifactDisplayPrefs())

    var sheetRequest by remember { mutableStateOf<ArtifactViewerHandoff.Entry?>(null) }

    val openFullscreen = remember(onNavigate, handoff) {
        { artifact: Artifact, versions: List<Artifact> ->
            handoff.put(artifact, versions)
            onNavigate(ArtifactFullscreen(artifact.identifier, artifact.version))
        }
    }
    val openArtifact = remember(openFullscreen) {
        { artifact: Artifact, versions: List<Artifact> ->
            if (displayPrefs.value.mode == ArtifactDisplayMode.FULLSCREEN) {
                openFullscreen(artifact, versions)
            } else {
                sheetRequest = ArtifactViewerHandoff.Entry(artifact, versions)
            }
        }
    }

    // Wrap both the content and the screen-root sheet so the pin action reaches inline cards and the
    // bottom-sheet viewer alike (no-op on platforms without a pin action).
    ProvideAddArtifactToHomeScreen {
        CompositionLocalProvider(LocalOpenArtifact provides openArtifact) {
            content()
        }

        sheetRequest?.let { req ->
            ArtifactPanel(
                artifact = req.artifact,
                versions = req.versions,
                onDismiss = { sheetRequest = null },
                onExpandFullscreen = openFullscreen,
            )
        }
    }
}

val chatSerializersModule = SerializersModule {
    polymorphic(NavKey::class) {
        subclass(NewChat::class, NewChat.serializer())
        subclass(Chat::class, Chat.serializer())
        subclass(PromptsLibrary::class, PromptsLibrary.serializer())
        subclass(PromptEditor::class, PromptEditor.serializer())
        subclass(ConversationMedia::class, ConversationMedia.serializer())
        subclass(ArtifactFullscreen::class, ArtifactFullscreen.serializer())
        subclass(ArtifactShortcutViewer::class, ArtifactShortcutViewer.serializer())
    }
}
