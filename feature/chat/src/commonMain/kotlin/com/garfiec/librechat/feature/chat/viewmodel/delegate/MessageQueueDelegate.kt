package com.garfiec.librechat.feature.chat.viewmodel.delegate

import com.garfiec.librechat.core.common.identity.ActiveAccountProvider
import com.garfiec.librechat.core.common.identity.currentAccountId
import com.garfiec.librechat.feature.chat.viewmodel.QueueHandle
import com.garfiec.librechat.feature.chat.viewmodel.QueuedMessage

/**
 * Owns the in-memory FIFO queue of follow-up messages the user staged while a reply was
 * streaming. Holds only the list + pause flag in its [QueueHandle]; the actual send is
 * delegated back to `ChatViewModel` via [sendWithSpec] so all the request-build / optimistic-
 * insert / lineage logic stays in one place.
 *
 * Draining is driven by the stream lifecycle: a successful `Final` calls [drainNext]; a Stop
 * or stream-error calls [pause] so nothing auto-fires until the user taps "Send queued"
 * ([resume]). The queue is never persisted — it lives and dies with the ViewModel.
 */
class MessageQueueDelegate(
    private val handle: QueueHandle,
    private val activeAccountProvider: ActiveAccountProvider,
    /** Fires one queued message. Set by `ChatViewModel` to `doSendWithSpec` (wrapped in the
     *  send-ready gate). Recomputes lineage live; only config comes from the spec. [awaitSettle]
     *  is true for the auto-drain after a Final (wait for the async reload to land the reply in
     *  the tree) and false for an explicit resume (the reply has already settled). */
    private val sendWithSpec: (spec: QueuedMessage, awaitSettle: Boolean) -> Unit,
    /** Called with the number of queued items dropped because they belonged to a now-inactive
     *  account (a switch happened since queueing). Set by `ChatViewModel` to surface a snackbar so a
     *  silently-discarded follow-up leaves a user-visible trail. */
    private val onQueuedDropped: (count: Int) -> Unit,
) {

    fun enqueue(spec: QueuedMessage) {
        handle.update { queue = queue.copy(messageQueue = queue.messageQueue + spec) }
    }

    /**
     * Pulls a queued item OUT of the queue for editing, returning it with its original index so
     * the caller can put it back in the same slot on commit/cancel. Does NOT touch the pause flag:
     * the item is only borrowed, so a paused queue stays paused. Returns null if the id is gone.
     */
    fun takeForEdit(localId: String): IndexedValue<QueuedMessage>? {
        val index = handle.state.messageQueue.indexOfFirst { it.localId == localId }
        if (index < 0) return null
        val item = handle.state.messageQueue[index]
        handle.update { queue = queue.copy(messageQueue = queue.messageQueue.filterNot { it.localId == localId }) }
        return IndexedValue(index, item)
    }

    /** Re-inserts an item at [index] (clamped to the current bounds) — the commit/cancel counterpart
     *  to [takeForEdit]. */
    fun reinsert(index: Int, item: QueuedMessage) {
        handle.update {
            val clamped = index.coerceIn(0, queue.messageQueue.size)
            queue = queue.copy(messageQueue = queue.messageQueue.toMutableList().apply { add(clamped, item) })
        }
    }

    /** Clears a now-meaningless pause when the queue is empty (e.g. an edit was committed empty,
     *  deleting the last item). */
    fun clearPauseIfEmpty() {
        handle.update { if (queue.messageQueue.isEmpty()) queue = queue.copy(isQueuePaused = false) }
    }

    fun cancel(localId: String) {
        handle.update {
            val next = queue.messageQueue.filterNot { it.localId == localId }
            // Clearing the last item while paused lifts the (now meaningless) pause.
            queue = queue.copy(messageQueue = next, isQueuePaused = queue.isQueuePaused && next.isNotEmpty())
        }
    }

    fun reorder(fromIndex: Int, toIndex: Int) {
        handle.update {
            val list = queue.messageQueue
            if (fromIndex !in list.indices || toIndex !in list.indices || fromIndex == toIndex) {
                return@update
            }
            val mutable = list.toMutableList()
            mutable.add(toIndex, mutable.removeAt(fromIndex))
            queue = queue.copy(messageQueue = mutable)
        }
    }

    /** Holds the queue after a Stop / stream-error. No-op when nothing is queued — but an item
     *  currently pulled out for editing still counts (it will return to the queue), so a Stop
     *  during the edit of the sole queued item correctly registers the pause. */
    fun pause() {
        if (handle.state.messageQueue.isEmpty() && !handle.state.isEditingQueued) return
        handle.update { queue = queue.copy(isQueuePaused = true) }
    }

    /** User tapped "Send queued": lift the pause and start draining. The reply already settled
     *  while paused, so no settle-wait is needed. */
    fun resume() {
        handle.update { queue = queue.copy(isQueuePaused = false) }
        drainNext(awaitSettle = false)
    }

    /**
     * Pops and fires the head of the queue. No-op when empty or paused. Called from the stream's
     * `Final` handler (by then `isStreaming` is already false, so firing the next send respects
     * the load-bearing "no Room write while streaming" invariant). [awaitSettle] defers the send
     * until the just-finished reply has landed in the tree (see [sendWithSpec]).
     */
    fun drainNext(awaitSettle: Boolean = true) {
        // Freeze the queue while a queued item is being edited: draining now would fire an item
        // out from under the user and shift the slots the edit session's originalIndex points at.
        // The edit's commit/cancel re-kicks draining once it completes.
        if (handle.state.isEditingQueued) return
        // A paused queue must not be mutated: leave every item (foreign ones included) in place until
        // the user resumes, which re-enters here with the pause lifted and runs the purge below. Guard
        // before the purge so a stray drain trigger can't silently drop items — and fire a snackbar —
        // on a queue the user has deliberately parked.
        if (handle.state.isQueuePaused) return
        // Purge items composed under a different account (the user switched accounts since queueing).
        // Sending one would POST account A's content to account B's server under B's bearer — a
        // content-to-wrong-server leak the same-owner reframe does not excuse. Surface a count so the
        // discard isn't silent. Only purge when the active account is resolved: a null current means the
        // identity is still unresolved (cold-start warm-up / logged-out), not "some other account", so
        // dropping every stamped item then would wipe the queue for no real mismatch. Items with no
        // accountId (pre-multi-account / tests) match any account.
        val current = activeAccountProvider.currentAccountId()?.value
        if (current != null) {
            val foreign = handle.state.messageQueue.count { it.accountId != null && it.accountId != current }
            if (foreign > 0) {
                handle.update {
                    queue = queue.copy(messageQueue = queue.messageQueue.filter { it.accountId == null || it.accountId == current })
                }
                onQueuedDropped(foreign)
            }
        }
        val head = handle.state.messageQueue.firstOrNull() ?: return
        handle.update { queue = queue.copy(messageQueue = queue.messageQueue.drop(1)) }
        sendWithSpec(head, awaitSettle)
    }
}
