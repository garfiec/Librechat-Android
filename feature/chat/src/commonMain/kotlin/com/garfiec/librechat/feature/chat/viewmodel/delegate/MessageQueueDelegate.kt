package com.garfiec.librechat.feature.chat.viewmodel.delegate

import com.garfiec.librechat.feature.chat.viewmodel.ChatStateHandle
import com.garfiec.librechat.feature.chat.viewmodel.QueuedMessage

/**
 * Owns the in-memory FIFO queue of follow-up messages the user staged while a reply was
 * streaming. Holds only the list + pause flag in [ChatStateHandle]; the actual send is
 * delegated back to `ChatViewModel` via [sendWithSpec] so all the request-build / optimistic-
 * insert / lineage logic stays in one place.
 *
 * Draining is driven by the stream lifecycle: a successful `Final` calls [drainNext]; a Stop
 * or stream-error calls [pause] so nothing auto-fires until the user taps "Send queued"
 * ([resume]). The queue is never persisted — it lives and dies with the ViewModel.
 */
class MessageQueueDelegate(
    private val stateHandle: ChatStateHandle,
    /** Fires one queued message. Set by `ChatViewModel` to `doSendWithSpec` (wrapped in the
     *  send-ready gate). Recomputes lineage live; only config comes from the spec. [awaitSettle]
     *  is true for the auto-drain after a Final (wait for the async reload to land the reply in
     *  the tree) and false for an explicit resume (the reply has already settled). */
    private val sendWithSpec: (spec: QueuedMessage, awaitSettle: Boolean) -> Unit,
) {

    fun enqueue(spec: QueuedMessage) {
        stateHandle.update { copy(messageQueue = messageQueue + spec) }
    }

    /**
     * Pulls a queued item OUT of the queue for editing, returning it with its original index so
     * the caller can put it back in the same slot on commit/cancel. Does NOT touch the pause flag:
     * the item is only borrowed, so a paused queue stays paused. Returns null if the id is gone.
     */
    fun takeForEdit(localId: String): IndexedValue<QueuedMessage>? {
        val index = stateHandle.state.messageQueue.indexOfFirst { it.localId == localId }
        if (index < 0) return null
        val item = stateHandle.state.messageQueue[index]
        stateHandle.update { copy(messageQueue = messageQueue.filterNot { it.localId == localId }) }
        return IndexedValue(index, item)
    }

    /** Re-inserts an item at [index] (clamped to the current bounds) — the commit/cancel counterpart
     *  to [takeForEdit]. */
    fun reinsert(index: Int, item: QueuedMessage) {
        stateHandle.update {
            val clamped = index.coerceIn(0, messageQueue.size)
            copy(messageQueue = messageQueue.toMutableList().apply { add(clamped, item) })
        }
    }

    /** Clears a now-meaningless pause when the queue is empty (e.g. an edit was committed empty,
     *  deleting the last item). */
    fun clearPauseIfEmpty() {
        stateHandle.update { if (messageQueue.isEmpty()) copy(isQueuePaused = false) else this }
    }

    fun cancel(localId: String) {
        stateHandle.update {
            val next = messageQueue.filterNot { it.localId == localId }
            // Clearing the last item while paused lifts the (now meaningless) pause.
            copy(messageQueue = next, isQueuePaused = isQueuePaused && next.isNotEmpty())
        }
    }

    fun reorder(fromIndex: Int, toIndex: Int) {
        stateHandle.update {
            val list = messageQueue
            if (fromIndex !in list.indices || toIndex !in list.indices || fromIndex == toIndex) {
                return@update this
            }
            val mutable = list.toMutableList()
            mutable.add(toIndex, mutable.removeAt(fromIndex))
            copy(messageQueue = mutable)
        }
    }

    /** Holds the queue after a Stop / stream-error. No-op when nothing is queued — but an item
     *  currently pulled out for editing still counts (it will return to the queue), so a Stop
     *  during the edit of the sole queued item correctly registers the pause. */
    fun pause() {
        if (stateHandle.state.messageQueue.isEmpty() && !stateHandle.state.isEditingQueued) return
        stateHandle.update { copy(isQueuePaused = true) }
    }

    /** User tapped "Send queued": lift the pause and start draining. The reply already settled
     *  while paused, so no settle-wait is needed. */
    fun resume() {
        stateHandle.update { copy(isQueuePaused = false) }
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
        if (stateHandle.state.isEditingQueued) return
        val head = stateHandle.state.messageQueue.firstOrNull() ?: return
        if (stateHandle.state.isQueuePaused) return
        stateHandle.update { copy(messageQueue = messageQueue.drop(1)) }
        sendWithSpec(head, awaitSettle)
    }
}
