package com.garfiec.librechat.feature.chat.viewmodel

import androidx.compose.runtime.Immutable

/**
 * Mid-run steering state (v0.8.8): the messages the user pushed into the *running* turn that
 * have not been injected yet.
 *
 * Distinct from [QueueState], which holds follow-ups waiting for the run to *finish*. A steer
 * changes the reply being written; a queued message becomes the next turn. Both surfaces sit
 * above the composer and a steer degrades into the queue whenever it cannot reach the run, so
 * the two are neighbours — but they are never the same list.
 *
 * In-memory only, like the queue: owned by
 * [com.garfiec.librechat.feature.chat.viewmodel.delegate.SteeringDelegate] and reset at every
 * stream-session boundary. The server's own copy is authoritative and is replayed on reconnect
 * via `resumeState.pendingSteers`.
 */
@Immutable
data class SteerState(
    /**
     * Steers awaiting injection, oldest first — the order the run will apply them in.
     *
     * This is a rendered *view*, derived from the delegate's own record of every steer this
     * ViewModel has seen. Settled steers (injected, cancelled, already re-homed) are tracked
     * there, not here: nothing renders them, and keeping them out of state means they cannot
     * participate in its equality.
     */
    val pendingSteers: List<PendingSteerChip> = emptyList(),
)

/** Lifecycle of a steer between the user sending it and the run injecting it. */
enum class SteerChipStatus {
    /** The POST is in flight; the chip's id is a client-minted placeholder. */
    SENDING,

    /** The server accepted it (202) and will inject it at the next tool-batch boundary. */
    PENDING,
}

/**
 * One steer the user has pushed into the live run, rendered as a chip above the composer until
 * the run injects it (`on_steer_applied`) or the user cancels it.
 *
 * There is deliberately no failed state. Every way a steer can fail to reach the run — the run
 * ended, paused, filled its queue, the server has no steer route, the request never left the
 * device — re-homes the text into the follow-up queue or sends it as a new turn instead. A chip
 * the user has to nurse back to life would be a worse answer than a queued message they can
 * already edit, reorder, and cancel.
 */
@Immutable
data class PendingSteerChip(
    /**
     * `local-…` while [status] is [SteerChipStatus.SENDING], replaced by the server's `steerId`
     * on the 202. Only the server id can be cancelled; a local one exists purely to key the row.
     */
    val steerId: String,
    val text: String,
    val status: SteerChipStatus,
    /**
     * When the user SENT it, not when the ack landed — the chip keeps this across the swap so
     * order reflects what the user did, not how fast each round-trip was.
     */
    val createdAt: Long,
) {
    /** Whether a cancel can be posted for this chip (a local placeholder has no server handle). */
    val isCancellable: Boolean get() = status == SteerChipStatus.PENDING
}
