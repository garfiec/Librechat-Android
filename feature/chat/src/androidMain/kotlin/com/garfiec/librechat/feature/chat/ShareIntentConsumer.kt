package com.garfiec.librechat.feature.chat

import android.net.Uri
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Data representing content shared to the app via Android share intents.
 *
 * @param text Shared text content (from EXTRA_TEXT). Pre-filled in the message input.
 * @param fileUris URIs of shared files/images (from EXTRA_STREAM). Attached as pending files.
 */
data class SharedContent(
    val text: String? = null,
    val fileUris: List<Uri> = emptyList(),
)

/**
 * Routes content shared into the app from another app to **one** chat composer: the one the user
 * is looking at.
 *
 * Delivery is two-step, because the two facts arrive from different places. `MainActivity` receives
 * the intent and knows the payload but not which screen is on top; the nav host knows the route but
 * not the payload. So the activity [stages][setPendingShare] the share as [undelivered], and the nav
 * host — which is also the thing that navigates to a new chat when the user is not on one — reads
 * the current route and [addresses][dispatchTo] it.
 *
 * ### Why addressed, and not broadcast
 * This used to be a `SharedFlow<Unit>` ping plus a one-shot `consume()`, and every live
 * `ChatViewModel` collected it. More than one is alive routinely — the `NewChat` landing sits in the
 * back stack beneath an open `Chat`, and two-pane scenes compose both — so whichever collector was
 * resumed first claimed the text, which on a phone is the *invisible* landing. Sharing into an open
 * conversation put the user's text in a composer they were not looking at and could only reach by
 * navigating back, i.e. lost it. Keying delivery to the conversation the nav host names (`null` for
 * the landing) removes the race rather than reordering it: a collector can only ever receive shares
 * addressed to it. This mirrors [com.garfiec.librechat.feature.chat.viewmodel.ServerFileSelectionHandoff],
 * which carries picker results back to their launcher for the same reason — see its KDoc for why
 * each collector installs a *fresh* channel (newest wins) and eviction is identity-guarded.
 *
 * Confined to the main thread: the activity stages on the main thread, the nav host dispatches from
 * a composition effect, and [sharesFor] is collected on the chat ViewModel's main-dispatched scope,
 * so the backing maps need no synchronization.
 */
object ShareIntentConsumer {

    private val _undelivered = MutableStateFlow<SharedContent?>(null)

    /**
     * A share that has arrived but has not been addressed to a chat yet. Held here rather than in
     * the activity so it survives an activity recreation with the intent already consumed (the
     * launch intent is only processed on a fresh start), which would otherwise strand it.
     */
    val undelivered: StateFlow<SharedContent?> = _undelivered.asStateFlow()

    /** The freshest live collector's channel per key (`null` = the `NewChat` landing). */
    private val current = mutableMapOf<String?, Channel<SharedContent>>()

    /** A share addressed to a key with no live collector yet; drained by the next subscriber. */
    private val pending = mutableMapOf<String?, SharedContent>()

    /** Stages an incoming share. Called by MainActivity when a share intent arrives. */
    fun setPendingShare(data: SharedContent) {
        _undelivered.value = data
    }

    /**
     * Delivers the staged share to [conversationId] (`null` for the `NewChat` landing). Called by
     * the nav host once it has resolved which chat is on screen. No-op when nothing is staged, so a
     * recomposition or a recreated activity cannot deliver the same share twice.
     */
    fun dispatchTo(conversationId: String?) {
        val data = _undelivered.value ?: return
        _undelivered.value = null
        val channel = current[conversationId]
        if (channel != null) {
            channel.trySend(data)
        } else {
            // The target screen may not have composed yet — dispatching straight after navigating to
            // a new chat is the normal case. Held for its first subscriber.
            pending[conversationId] = data
        }
    }

    /**
     * Shares addressed to [conversationId]; collected by that conversation's chat screen
     * (`null` for the `NewChat` landing, which has no id yet).
     */
    fun sharesFor(conversationId: String?): Flow<SharedContent> = flow {
        val channel = Channel<SharedContent>(Channel.BUFFERED)
        // Become the current channel for this key (newest collector wins), so a churning
        // same-key predecessor can neither share nor evict this channel.
        current[conversationId] = channel
        // Drain anything dispatched before we subscribed.
        pending.remove(conversationId)?.let { channel.trySend(it) }
        try {
            emitAll(channel.receiveAsFlow())
        } finally {
            // Identity-guarded: only clear the entry if we're still the current channel, so a
            // disposing predecessor can't remove a successor's channel.
            if (current[conversationId] === channel) current.remove(conversationId)
        }
    }

    /** Drops all routing state. Process-global singleton, so tests must start from empty. */
    internal fun resetForTest() {
        _undelivered.value = null
        current.clear()
        pending.clear()
    }
}
