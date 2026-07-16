package com.garfiec.librechat.feature.conversations.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.garfiec.librechat.feature.conversations.export.ExportFormat
import com.garfiec.librechat.feature.conversations.platform.FileSaver
import com.garfiec.librechat.feature.conversations.platform.copyToClipboard
import com.garfiec.librechat.feature.conversations.platform.showToast
import com.garfiec.librechat.feature.conversations.resources.*
import com.garfiec.librechat.feature.conversations.resources.Res
import com.garfiec.librechat.feature.conversations.viewmodel.ConversationListEvent
import kotlinx.coroutines.flow.Flow
import org.jetbrains.compose.resources.stringResource

// Hoisted: compiled once instead of per export event.
private val ExportFileNameSanitizer = Regex("[^a-zA-Z0-9._-]")

/**
 * Hosts the platform side-effects for conversation actions driven by [ConversationListEvent]:
 * copies share links to the clipboard, saves exports via the platform file picker, surfaces
 * errors as toasts, and forwards duplicate/navigation events. Keeps the clipboard/toast/file-save
 * helpers and their localized strings in this module so callers in other modules (e.g. the
 * navigation drawer) can reuse the full action set without duplicating resources.
 */
@Composable
fun ConversationActionEffects(
    events: Flow<ConversationListEvent>,
    onNavigateToConversation: (String) -> Unit,
    onNavigateToNewChat: () -> Unit,
) {
    var pendingExportFileName by remember { mutableStateOf<String?>(null) }
    var pendingExportContent by remember { mutableStateOf<String?>(null) }

    val currentOnNavigate by rememberUpdatedState(onNavigateToConversation)
    val currentOnNavigateToNewChat by rememberUpdatedState(onNavigateToNewChat)

    val linkCopiedMsg = stringResource(Res.string.link_copied)
    val conversationExportedMsg = stringResource(Res.string.conversation_exported)

    FileSaver(
        triggerFileName = pendingExportFileName,
        content = pendingExportContent,
        onComplete = { success, errorMessage ->
            if (success) {
                showToast(conversationExportedMsg)
            } else if (errorMessage != null) {
                showToast(errorMessage)
            }
        },
        onReset = {
            pendingExportFileName = null
            pendingExportContent = null
        },
    )

    LaunchedEffect(Unit) {
        events.collect { event ->
            when (event) {
                is ConversationListEvent.ShareLinkCopied -> {
                    copyToClipboard(event.url, "Share Link")
                    showToast(linkCopiedMsg)
                }
                is ConversationListEvent.NavigateToConversation -> {
                    currentOnNavigate(event.conversationId)
                }
                is ConversationListEvent.NavigateToNewChat -> {
                    currentOnNavigateToNewChat()
                }
                is ConversationListEvent.ShowError -> {
                    showToast(event.message)
                }
                is ConversationListEvent.ExportReady -> {
                    pendingExportContent = event.content
                    val ext = when (event.format) {
                        ExportFormat.JSON -> "json"
                        ExportFormat.MARKDOWN -> "md"
                    }
                    val safeTitle = event.title.replace(ExportFileNameSanitizer, "_")
                    pendingExportFileName = "$safeTitle.$ext"
                }
                is ConversationListEvent.ImportSuccess -> { /* not used by the drawer */ }
            }
        }
    }
}
