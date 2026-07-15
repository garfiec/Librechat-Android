package com.garfiec.librechat.feature.chat.viewmodel

import com.garfiec.librechat.core.model.FileObject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
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
 * consume and drop the element, so the launcher never sees it. Keying to the launching
 * [conversationId][publish] (matching the recipe's per-key `channelMap`) delivers each selection
 * straight to its launcher's own collector — no race, no drop.
 *
 * ### Why a **fresh channel per collector** (newest wins the key)
 * Two *generations* of the SAME key can be alive at once: starting an agent (or a model shortcut)
 * runs `navigateToTopLevel(NewChat(agentId))`, which replaces the bare `NewChat()` entry — so the
 * outgoing landing ViewModel and its replacement (both keyed `null`) briefly coexist, the new one
 * composed before the old one is disposed. If both collectors shared one `getOrPut` channel, the
 * outgoing ViewModel's disposal would evict that channel out from under the live successor, and the
 * next [publish] would then create a brand-new channel nobody listens on — silently dropping the
 * selection (no attachment chip). To prevent that:
 * - each [selectionsFor] collector installs its **own** fresh channel as the [current] one for its
 *   key (the newest collector wins), so two generations never share a channel; and
 * - eviction is **identity-guarded** — a completing collector only clears the map entry if it is
 *   still the current channel, so an outgoing generation can't evict its successor's channel.
 * [publish] always targets the [current] channel, i.e. the freshest live collector.
 *
 * ### Staging a selection published before its collector subscribes
 * A selection can be [publish]ed while no collector is currently registered for the key (e.g. the
 * launching screen is being recreated across a config change as the picker returns). Those files
 * are held in [pending] and drained by the next collector when it subscribes, so a returning screen
 * still receives them. [pending] holds at most one entry per key and is bounded by the number of
 * distinct conversations that ever launch the picker while briefly not collecting; each entry is
 * removed the moment a collector subscribes. The only entry that can outlive the process is one for
 * a key whose screen is destroyed before ever collecting again (e.g. the target conversation is
 * deleted) — a single small list, not unbounded growth.
 *
 * Confined to the main thread: [publish] runs from the picker's confirm callback and
 * [selectionsFor] is collected on the chat ViewModel's main-dispatched scope, so the backing maps
 * need no synchronization.
 */
class ServerFileSelectionHandoff {

    /** The freshest live collector's channel per key (`null` = the `NewChat` landing). */
    private val current = mutableMapOf<String?, Channel<List<FileObject>>>()

    /** Files published for a key that had no live collector yet; drained by the next subscriber. */
    private val pending = mutableMapOf<String?, List<FileObject>>()

    /**
     * Files staged for [conversationId]; collected by that conversation's chat screen.
     *
     * @param conversationId the conversation collecting results (`null` for the `NewChat` landing).
     */
    fun selectionsFor(conversationId: String?): Flow<List<FileObject>> = flow {
        val channel = Channel<List<FileObject>>(Channel.BUFFERED)
        // Become the current channel for this key (newest collector wins), so a churning
        // same-key predecessor can neither share nor evict this channel.
        current[conversationId] = channel
        // Drain anything published before we subscribed.
        pending.remove(conversationId)?.let { channel.trySend(it) }
        try {
            emitAll(channel.receiveAsFlow())
        } finally {
            // Identity-guarded: only clear the entry if we're still the current channel, so a
            // disposing predecessor can't remove a successor's channel.
            if (current[conversationId] === channel) current.remove(conversationId)
        }
    }

    /**
     * Stages the picked files for [targetConversationId] (the conversation that launched the
     * picker; `null` for the `NewChat` landing, which has no id yet). Ignored when the list is empty.
     * Delivered to the current live collector if there is one, otherwise held for the next subscriber.
     */
    fun publish(targetConversationId: String?, files: List<FileObject>) {
        if (files.isEmpty()) return
        val channel = current[targetConversationId]
        if (channel != null) {
            channel.trySend(files)
        } else {
            pending[targetConversationId] = files
        }
    }
}
