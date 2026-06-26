package com.garfiec.librechat.feature.chat.viewmodel

import com.garfiec.librechat.core.model.FileObject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * In-process, one-shot handoff carrying the files a user picked on the server-file picker
 * (`FilesPicker` route) back to the chat composer that launched it.
 *
 * Why a [Channel] and not a `StateFlow`: the result must be delivered to exactly one consumer,
 * once. Navigation has no built-in result channel here — this mirrors the event-based result
 * pattern in the official Navigation 3 recipes (`ResultEventBus`), and the codebase's own
 * [NewChatSelectionHandoff] / `ArtifactViewerHandoff`.
 *
 * Why a channel **per launcher** rather than one shared channel filtered by id: multiple chat
 * screens can collect at once (the `NewChat` landing sits in the back stack beneath an active
 * `Chat`, and two-pane scenes compose both at once). A single [Channel] fans each element out to
 * whichever collector receives first; if that is not the launcher, a filtered collector would
 * consume and drop the element, so the launcher never sees it. Keying a channel to the launching
 * [conversationId][publish] (matching the recipe's per-key `channelMap`) delivers each selection
 * straight to its launcher's own collector — no race, no drop.
 *
 * Confined to the main thread: [publish] runs from the picker's confirm callback and
 * [selectionsFor] is collected on the chat ViewModel's main-dispatched scope, so the backing map
 * needs no synchronization. A buffered channel lets [publish] stage the selection before the
 * returning chat resumes collecting. Each conversation's channel is evicted when its collector
 * completes (the ViewModel is destroyed), so the map can't grow for the whole process lifetime.
 */
class ServerFileSelectionHandoff {

    /** Lazily-created buffered channel per launching conversation (`null` = the `NewChat` landing). */
    private val channels = mutableMapOf<String?, Channel<List<FileObject>>>()

    private fun channelFor(conversationId: String?): Channel<List<FileObject>> =
        channels.getOrPut(conversationId) { Channel(Channel.BUFFERED) }

    /**
     * Files staged for [conversationId]; collected by that conversation's chat screen.
     *
     * @param conversationId the conversation collecting results (`null` for the `NewChat` landing).
     */
    fun selectionsFor(conversationId: String?): Flow<List<FileObject>> =
        channelFor(conversationId).receiveAsFlow()
            .onCompletion { channels.remove(conversationId) }

    /**
     * Stages the picked files for [targetConversationId] (the conversation that launched the
     * picker; `null` for the `NewChat` landing, which has no id yet). Ignored when the list is empty.
     */
    fun publish(targetConversationId: String?, files: List<FileObject>) {
        if (files.isEmpty()) return
        channelFor(targetConversationId).trySend(files)
    }
}
