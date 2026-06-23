package com.garfiec.librechat.feature.chat.viewmodel

import com.garfiec.librechat.core.model.FileObject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * In-process, one-shot handoff carrying the files a user picked on the server-file picker
 * (`FilesPicker` route) back to the chat composer that launched it.
 *
 * Why a [Channel] and not a `StateFlow`: the result must be delivered to exactly one consumer,
 * once. Navigation has no built-in result channel here (the pattern mirrors
 * [NewChatSelectionHandoff] / `ArtifactViewerHandoff`).
 *
 * Multiple chat ViewModels can be alive at once (the `NewChat` landing stays in the back stack
 * beneath an active `Chat`). A bare [Channel] fans each element out to whichever collector receives
 * first, with no guarantee it is the launcher — so every [Selection] carries the
 * [targetConversationId][Selection.targetConversationId] captured when the picker was opened, and a
 * chat screen attaches the files only when it matches its own conversation. A buffered channel lets
 * the picker [publish] before the returning screen recomposes; the matching collector consumes it
 * once, and a mismatched/stale selection is dropped rather than mis-attached to the wrong chat.
 */
class ServerFileSelectionHandoff {

    /**
     * @param targetConversationId the conversation that launched the picker (`null` for the
     *   `NewChat` landing, which has no id yet). Used to route the result to its launcher.
     */
    data class Selection(
        val targetConversationId: String?,
        val files: List<FileObject>,
    )

    private val channel = Channel<Selection>(Channel.BUFFERED)

    /** Selections emitted by the picker; collected by the foreground chat screen. */
    val selections: Flow<Selection> = channel.receiveAsFlow()

    /** Stages the picked files for [targetConversationId]. Ignored when the list is empty. */
    fun publish(targetConversationId: String?, files: List<FileObject>) {
        if (files.isEmpty()) return
        channel.trySend(Selection(targetConversationId, files))
    }
}
