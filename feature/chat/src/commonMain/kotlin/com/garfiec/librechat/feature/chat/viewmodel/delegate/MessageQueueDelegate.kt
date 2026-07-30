package com.garfiec.librechat.feature.chat.viewmodel.delegate

import com.garfiec.librechat.core.common.identity.ActiveAccountProvider
import com.garfiec.librechat.core.common.identity.currentAccountId
import com.garfiec.librechat.feature.chat.viewmodel.QueueHandle
import com.garfiec.librechat.feature.chat.viewmodel.QueuedMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeMark
import kotlin.time.TimeSource

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
    /**
     * Renews the upload-window hold on file ids waiting in the queue (v0.8.8
     * `POST /api/files/usage`). Best-effort; a server without the route ignores it. Suspends
     * until the request settles so [startHoldRenewal]'s loop can be its own overlap guard.
     */
    private val markFilesUsed: suspend (List<String>) -> Unit = {},
    /** Whether the server exposes `POST /api/files/usage`. Asked before the heartbeat starts:
     *  [markFilesUsed] already no-ops without the route, but the loop would otherwise wake every
     *  30 minutes for the ViewModel's whole life to call something that does nothing. */
    private val holdRenewalSupported: () -> Boolean = { true },
    /** Clock the renewal heartbeat measures elapsed time with. Injected so a test can drive it
     *  in step with the virtual scheduler — production always uses the monotonic default. */
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {

    private var holdRenewalJob: Job? = null
    private var lastRenewedAt: TimeMark = timeSource.markNow()

    fun enqueue(spec: QueuedMessage) {
        handle.update { queue = queue.copy(messageQueue = queue.messageQueue + spec) }
        // A queued message can outlive the upload window it was composed in — a long run, a
        // human-review pause, a queue the user leaves paused — and its attachments get reaped
        // out from under it. Touching them at queue time is the whole reason the route exists.
        val fileIds = spec.attachments.mapNotNull { it.fileId }
        if (fileIds.isEmpty()) return
        handle.scope.launch { markFilesUsed(fileIds) }
        startHoldRenewal()
    }

    /**
     * Starts the hold-renewal heartbeat (upstream #14470 / `useQueueDrain`'s 30-minute tick).
     *
     * `POST /api/files/usage` stopped being a permanent release: it now takes a *bounded* hold,
     * `expiresAt = max(expiresAt, min(now + renewMs, createdAt + maxLifetimeMs))` with
     * `renewMs = 24 h + approvalTtl`. A client that touches once and stops therefore lapses one
     * `renewMs` after that touch, so [enqueue]'s single touch is no longer the whole story for a
     * queue the user leaves parked.
     *
     * **Lives only as long as there is something to hold.** Started by [enqueue] and [reinsert]
     * whenever an item with uploaded attachments joins the queue, and returns as soon as a tick
     * finds no file ids left — a later add starts a fresh loop. Upstream heartbeats "while anything is queued" for the
     * same reason, and it keeps an idle chat from parking a coroutine that can never have work.
     *
     * **The loop delays first and renews second, deliberately.** Renewing at the top would fire
     * an unspaced request on every cold start, and a kill/relaunch cycle would turn that into a
     * burst against a route that now meters per user (`FILE_USAGE_USER_MAX` 120 / 15 min) and
     * logs a scored `FILE_UPLOAD_LIMIT` violation on a breach. Delaying first means a fresh
     * process is silent for a full interval, and the first tick is the earliest possible request.
     *
     * **Explicitly a no-op once this ViewModel or the process is gone.** The loop runs on the
     * ViewModel's scope and the queue is never persisted, so when either dies there is nothing
     * left to protect — the same reasoning upstream applies to an abandoned browser tab. Note
     * the platforms differ in how soon that happens: iOS suspends a backgrounded app quickly,
     * while Android only freezes cached processes from API 34+ and this app is `minSdk 26`, so
     * on Android 8–13 a backgrounded process keeps ticking. That is the direction that costs
     * nothing (a live queue keeps being protected), so it needs no extra handling.
     *
     * Idempotent — a second call while the loop is live is ignored.
     */
    private fun startHoldRenewal() {
        if (holdRenewalJob?.isActive == true) return
        if (!holdRenewalSupported()) return
        // Everything queued right now was just touched by the enqueue that got us here (the loop
        // only starts from an idle state), so the interval genuinely starts at this instant.
        lastRenewedAt = timeSource.markNow()
        holdRenewalJob = handle.scope.launch {
            while (isActive) {
                // `delay` is a scheduling hint, not a measurement: Android's Main dispatcher
                // schedules on the monotonic uptime clock and iOS's on wall time, so neither
                // return is proof that the interval actually elapsed. The monotonic mark below
                // is the authority; `delay` only decides when to come back and look.
                delay((RENEW_INTERVAL - lastRenewedAt.elapsedNow()).coerceAtLeast(MIN_TICK))
                if (lastRenewedAt.elapsedNow() < RENEW_INTERVAL) continue
                val fileIds = queuedFileIds()
                // The queue drained (or nothing left in it carries an upload): stop rather than
                // spin, and let the next enqueue start a fresh loop.
                if (fileIds.isEmpty()) return@launch
                // A single sequential loop that AWAITS the renewal is the overlap guard: the
                // next delay does not even start until this request settles, so a slow tick
                // cannot be joined by the one behind it.
                markFilesUsed(fileIds)
                // Marked after the call, so the interval measures quiet time between requests —
                // a renewal that takes longer than the interval is not immediately followed by
                // another one.
                lastRenewedAt = timeSource.markNow()
            }
        }
    }

    /**
     * Every file id currently held for the queue, deduped. A plain read of the live queue rather
     * than a set the loop is keyed on: restarting the ticker whenever the id set changes would
     * let ordinary queue edits thrash it, and the cost of renewing an id that was just drained
     * is one wasted entry in a batch the repository chunks at ten anyway.
     */
    private fun queuedFileIds(): List<String> =
        handle.state.messageQueue.flatMap { spec -> spec.attachments.mapNotNull { it.fileId } }
            .distinct()

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

    /**
     * Re-inserts an item at [index] (clamped to the current bounds) — the commit/cancel counterpart
     * to [takeForEdit], and also how a refused drain puts its item back at the head.
     *
     * Restarts the heartbeat, because none of those paths go through [enqueue]. The loop exits as
     * soon as a tick finds an empty queue, so a drain that empties the queue and is then refused
     * would put an attachment back with no ticker and no enqueue-time touch — exactly the
     * untouched-queue lapse the renewal exists to prevent. Idempotent while a loop is live.
     */
    fun reinsert(index: Int, item: QueuedMessage) {
        handle.update {
            val clamped = index.coerceIn(0, queue.messageQueue.size)
            queue = queue.copy(messageQueue = queue.messageQueue.toMutableList().apply { add(clamped, item) })
        }
        if (item.attachments.any { it.fileId != null }) startHoldRenewal()
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

    private companion object {
        /** Matches upstream's `useQueueDrain` heartbeat. Anything under ~12 h clears the
         *  server's unconditional 24 h hold floor; 30 min leaves headroom if a deployment ever
         *  shortens it, and at one tick per 30 min the 15-minute rate window sees at most one
         *  tick — times `ceil(ids / 10)` batches, so ~1,200 queued ids would be needed to
         *  approach the 120-request limit. The repository's ten-per-call chunking is what makes
         *  that arithmetic hold; do not raise it. */
        val RENEW_INTERVAL = 30.minutes

        /** Floor on a single sleep so a monotonic clock that fails to advance cannot spin the
         *  loop. Only reachable when the computed remainder is non-positive; no request is
         *  issued on such a pass, because the elapsed check runs first. */
        val MIN_TICK = 1.minutes
    }
}
