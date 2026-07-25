package com.garfiec.librechat.feature.chat.viewmodel.delegate

import co.touchlab.kermit.Logger
import com.garfiec.librechat.core.common.result.ApiException
import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.data.repository.ChatRepository
import com.garfiec.librechat.core.model.PendingSteer
import com.garfiec.librechat.core.model.request.SteerCancelRequest
import com.garfiec.librechat.core.model.request.SteerRequest
import com.garfiec.librechat.core.model.steer.SteerFallback
import com.garfiec.librechat.core.model.steer.parseSteerRejectionCode
import com.garfiec.librechat.core.model.steer.steerFallbackFor
import com.garfiec.librechat.feature.chat.viewmodel.PendingSteerChip
import com.garfiec.librechat.feature.chat.viewmodel.QueuedMessage
import com.garfiec.librechat.feature.chat.viewmodel.SteerChipStatus
import com.garfiec.librechat.feature.chat.viewmodel.SteerState
import com.garfiec.librechat.feature.chat.viewmodel.SteeringHandle
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Owns mid-run steering (v0.8.8): pushing a message into the turn that is *already generating*
 * so the model changes course without the reply being restarted.
 *
 * The delegate holds the pending-steer chips and posts to `/api/agents/chat/steer`. It never
 * touches the stream — an accepted steer changes what the run writes, and the injection comes
 * back as an ordinary `on_steer_applied` event on the SSE connection already open.
 *
 * **Nothing here may lose the user's text.** Steering is best-effort by construction: the run
 * can end, pause, or fill its queue between the user hitting send and the request landing. So
 * every failure path re-homes the words — into the follow-up queue if a run still looks live, or
 * as an ordinary new turn if it is over. That is also why there is no failed-chip state: a
 * queued message the user can already edit, reorder, and cancel is a better home for text than a
 * dead chip they would have to nurse back to life.
 *
 * Each steer carries the send spec it would have become, minted from the composer at send time.
 * Rebuilding one at failure time would read whatever model, tools, and attachments the composer
 * holds by then — seconds later, after the user has moved on.
 */
class SteeringDelegate(
    private val handle: SteeringHandle,
    private val chatRepository: ChatRepository,
    /**
     * Snapshots the current send config around arbitrary text. Used for steers reported by the
     * *server* (a reconnect, another device, a leftover report), which arrive as bare text with
     * no spec of their own. Null when the config cannot produce a sendable message.
     */
    private val buildFollowUp: (String) -> QueuedMessage?,
    /** Holds a message as a follow-up for after the run; the queue's own drain fires it. */
    private val enqueueFollowUp: (QueuedMessage) -> Unit,
    /**
     * Sends a message as an ordinary new turn. Used only when the server says the run is over
     * AND the client agrees, where queueing would leave the item with no run-end left to drain
     * it.
     */
    private val sendAsNewTurn: (QueuedMessage) -> Unit,
    /** True while the client still believes a run is in flight (decides which fallback applies). */
    private val isStreaming: () -> Boolean,
) {

    /**
     * The send spec each in-flight or queued steer would fall back to, keyed by the id the chip
     * currently carries (local placeholder, then the server's).
     *
     * Kept out of [SteerState] because nothing renders it and it must not participate in state
     * equality — it exists only to survive from send time to whenever the steer fails.
     */
    private val fallbackSpecs = mutableMapOf<String, QueuedMessage>()

    /**
     * Placeholder ids the user cancelled while their POST was still in flight.
     *
     * A `SENDING` chip has no server handle yet, so its cancel cannot be posted when it is
     * asked for — it has to wait for the ack that mints the id. Without this record the ack
     * would re-add the chip the user just dismissed and the steer would be injected anyway.
     */
    private val cancelledWhileSending = mutableSetOf<String>()

    /**
     * Local ids whose POST has not answered yet.
     *
     * [clear] runs on every session boundary, but a steer's POST resumes on a scope that outlives
     * the stream — so an ack landing after the run ended finds whatever [clear] left behind. If it
     * finds no spec it re-homes nothing and the user's words are gone, and if it finds no cancel
     * record it never withdraws the steer the user already dismissed. These ids are therefore
     * exempt from the wipe until their own coroutine settles.
     */
    private val outstandingSends = mutableSetOf<String>()

    /**
     * Steers [fallback]'s text into the run on [conversationId].
     *
     * Optimistic: the chip appears immediately under a client-minted id and swaps to the
     * server's id when the 202 lands. The caller has already decided steering is available; this
     * does not re-check, it only degrades when the server disagrees.
     */
    @OptIn(ExperimentalUuidApi::class)
    fun steer(conversationId: String, fallback: QueuedMessage) {
        val trimmed = fallback.text.trim()
        if (trimmed.isEmpty()) return
        val localId = "local-${Uuid.random()}"
        // The submission time, carried through the id swap: chips must sort by when the user
        // sent them, not by how long each round-trip took, or a steer sent first can end up
        // displayed behind one sent after it.
        val createdAt = Clock.System.now().toEpochMilliseconds()
        fallbackSpecs[localId] = fallback
        outstandingSends += localId
        upsertChip(PendingSteerChip(localId, trimmed, SteerChipStatus.SENDING, createdAt))

        handle.scope.launch {
            val result = try {
                chatRepository.steerChat(SteerRequest(conversationId, trimmed))
            } finally {
                // Settled: this id no longer needs protection from clear(). Dropped before the
                // branches below rather than after, because they are the ones that consume the
                // spec, and nothing can call clear() between here and them (no suspension left).
                outstandingSends -= localId
            }
            when (result) {
                is Result.Success ->
                    acknowledge(conversationId, localId, result.data.steerId, trimmed, createdAt)

                is Result.Error -> {
                    Logger.d(result.exception) { "Steer rejected: ${result.message}" }
                    val spec = fallbackSpecs.remove(localId)
                    dropChip(localId)
                    // A steer the user cancelled mid-flight must not come back as a queued
                    // follow-up: they withdrew the words, not just the delivery route.
                    if (cancelledWhileSending.remove(localId)) return@launch
                    if (spec != null) degrade(spec, rejectionCode(result))
                }

                is Result.Loading -> {
                    fallbackSpecs.remove(localId)
                    cancelledWhileSending.remove(localId)
                    dropChip(localId)
                }
            }
        }
    }

    /**
     * Settles an accepted steer onto its server id.
     *
     * Three races resolve here, each of which would otherwise strand a chip forever:
     * - the user cancelled before the id existed — post the cancel now that it does;
     * - the applied event beat the ack, so the steer is already in the reply — drop the chip;
     * - the run ended while the ack was in flight, so no injection is coming and no later event
     *   will ever retire the chip — re-home the text as a follow-up instead.
     */
    private fun acknowledge(
        conversationId: String,
        localId: String,
        serverId: String?,
        text: String,
        createdAt: Long,
    ) {
        val spec = fallbackSpecs.remove(localId)
        val wasCancelled = cancelledWhileSending.remove(localId)
        // A 202 with no id is unusable: it can be neither cancelled nor matched to an applied
        // event, so treat it as un-steered rather than showing a chip that can never resolve.
        if (serverId.isNullOrBlank()) {
            dropChip(localId)
            if (!wasCancelled && spec != null) enqueueFollowUp(spec)
            return
        }
        if (wasCancelled) {
            dropChip(localId)
            postCancel(conversationId, serverId)
            return
        }
        if (serverId in handle.state.steer.appliedSteerIds) {
            dropChip(localId)
            return
        }
        if (!isStreaming()) {
            dropChip(localId)
            if (spec != null) enqueueFollowUp(spec)
            return
        }
        if (spec != null) fallbackSpecs[serverId] = spec
        handle.update {
            val remaining = steer.pendingSteers.filterNot { it.steerId == localId }
            // Upsert rather than append: a sync frame may already have re-seeded this steer
            // under its server id while the ack was in flight.
            steer = if (remaining.any { it.steerId == serverId }) {
                steer.copy(pendingSteers = remaining)
            } else {
                steer.copy(
                    pendingSteers = remaining + PendingSteerChip(
                        steerId = serverId,
                        text = text,
                        status = SteerChipStatus.PENDING,
                        createdAt = createdAt,
                    ),
                )
            }
        }
    }

    /**
     * Routes a message the run refused to take.
     *
     * [SteerFallback.SEND_NOW] applies only when the server reported the run gone AND the client
     * agrees it has stopped. While the client still believes a run is live — the common case,
     * because the final frame is usually still settling — the message goes to the queue, whose
     * drain fires it the moment the run ends. Sending directly there would hit the send path's
     * own in-flight guard and be dropped.
     */
    private fun degrade(spec: QueuedMessage, code: String?) {
        when (steerFallbackFor(code)) {
            SteerFallback.SEND_NOW -> if (isStreaming()) enqueueFollowUp(spec) else sendAsNewTurn(spec)
            SteerFallback.QUEUE -> enqueueFollowUp(spec)
        }
    }

    private fun rejectionCode(result: Result.Error): String? =
        parseSteerRejectionCode((result.exception as? ApiException)?.body)

    /**
     * A steer reached the run and is now part of the reply (`on_steer_applied`).
     *
     * The id is recorded even when no chip matches: the event can arrive before this client's
     * own 202, and the record is what stops that ack from re-minting a chip for a steer already
     * in the content.
     */
    fun onSteerApplied(steerId: String) {
        if (steerId.isBlank()) return
        fallbackSpecs.remove(steerId)
        handle.update {
            steer = steer.copy(
                pendingSteers = steer.pendingSteers.filterNot { it.steerId == steerId },
                appliedSteerIds = (steer.appliedSteerIds + steerId)
                    .takeLast(SteerState.MAX_APPLIED_IDS),
            )
        }
    }

    /**
     * Replaces the chips with the server's still-queued steers, from a reconnect's
     * `resumeState.pendingSteers`.
     *
     * The server's list is authoritative — it knows what was injected while this client was away
     * — so an empty one correctly clears stale chips. In-flight local chips are kept: their own
     * POST has not answered yet, and by definition their ids are not in the server's list.
     */
    fun onPendingSteersSynced(steers: List<PendingSteer>) {
        val synced = steers.mapNotNull { it.toChip() }
        handle.update {
            val inFlight = steer.pendingSteers.filter { it.status == SteerChipStatus.SENDING }
            steer = steer.copy(pendingSteers = (synced + inFlight).sortedBy { it.createdAt })
        }
    }

    /**
     * Takes back steers the run accepted but never injected — reported on the `final` frame, the
     * abort ack, and `/chat/status`'s `unrecoveredSteers`.
     *
     * All three are claim-on-read: the server drops its copy as it hands them over, so this is
     * the last chance to keep the words. They become queued follow-ups, because the run they
     * were meant to steer is finished and the user still asked for these things to be said.
     *
     * Steers this client sent keep the spec they were composed with; ones it only learned about
     * here (another device, a reconnect) get a fresh snapshot of the current config.
     */
    fun reclaim(steers: List<PendingSteer>) {
        if (steers.isEmpty()) return
        val reclaimed = steers.mapNotNull { it.toChip() }.sortedBy { it.createdAt }
        if (reclaimed.isEmpty()) return
        handle.update {
            val ids = reclaimed.map { it.steerId }.toSet()
            steer = steer.copy(pendingSteers = steer.pendingSteers.filterNot { it.steerId in ids })
        }
        reclaimed.forEach { chip -> requeue(chip) }
    }

    /**
     * Converts chips the run left behind into queued follow-ups when no server report arrives to
     * reclaim them — a stream that dies on an error carries no `pendingSteers`, but the text of
     * every accepted chip is still held here.
     *
     * Only [SteerChipStatus.PENDING] chips convert: a `SENDING` chip's POST has not answered yet
     * and will re-home its own text through the degrade path, so taking it here as well would
     * send the same message twice.
     */
    fun reclaimLocalChips() {
        val settled = handle.state.steer.pendingSteers
            .filter { it.status == SteerChipStatus.PENDING }
            .sortedBy { it.createdAt }
        if (settled.isEmpty()) return
        handle.update {
            steer = steer.copy(
                pendingSteers = steer.pendingSteers.filter { it.status == SteerChipStatus.SENDING },
            )
        }
        settled.forEach { chip -> requeue(chip) }
    }

    private fun requeue(chip: PendingSteerChip) {
        val spec = fallbackSpecs.remove(chip.steerId) ?: buildFollowUp(chip.text) ?: return
        enqueueFollowUp(spec)
    }

    /** Withdraws a queued steer. Optimistic — the row goes immediately, the POST just confirms. */
    fun cancel(steerId: String) {
        val chip = handle.state.steer.pendingSteers.firstOrNull { it.steerId == steerId } ?: return
        fallbackSpecs.remove(steerId)
        dropChip(steerId)
        val conversationId = handle.state.conversationId ?: return
        // A `SENDING` chip has no server id to cancel yet — record the intent so its own ack
        // cancels the real steer instead of resurrecting the chip.
        if (chip.isCancellable) postCancel(conversationId, steerId) else cancelledWhileSending += steerId
    }

    private fun postCancel(conversationId: String, steerId: String) {
        handle.scope.launch {
            val result = chatRepository.cancelSteer(SteerCancelRequest(conversationId, steerId))
            if (result is Result.Error) {
                // The steer may still be injected; the applied event and the reply's own content
                // are authoritative either way, so nothing is restored here.
                Logger.d(result.exception) { "Steer cancel failed: ${result.message}" }
            }
        }
    }

    /**
     * Session boundary: drops every chip and the applied-id record.
     *
     * Chips describe one run's queue, so carrying them into the next stream would show pending
     * work against a run that never accepted it. Text still owed to the user has already been
     * re-homed by [reclaim] / [reclaimLocalChips] on the ending frame; this only clears display
     * state.
     *
     * The exception is a steer whose POST is still in flight ([outstandingSends]): neither
     * reclaim path takes a `SENDING` chip, so its own continuation is the only thing left that
     * can re-home the text — and it needs the spec and cancel record to do it.
     */
    fun clear() {
        fallbackSpecs.keys.retainAll(outstandingSends)
        cancelledWhileSending.retainAll(outstandingSends)
        if (handle.state.steer == SteerState()) return
        handle.update { steer = SteerState() }
    }

    private fun upsertChip(chip: PendingSteerChip) {
        handle.update {
            val others = steer.pendingSteers.filterNot { it.steerId == chip.steerId }
            steer = steer.copy(pendingSteers = (others + chip).sortedBy { it.createdAt })
        }
    }

    private fun dropChip(steerId: String) {
        if (handle.state.steer.pendingSteers.none { it.steerId == steerId }) return
        handle.update {
            steer = steer.copy(pendingSteers = steer.pendingSteers.filterNot { it.steerId == steerId })
        }
    }

    /** Server records with no id or no text can be neither cancelled nor replayed; drop them. */
    private fun PendingSteer.toChip(): PendingSteerChip? {
        val id = steerId?.takeIf { it.isNotBlank() } ?: return null
        val body = text?.takeIf { it.isNotBlank() } ?: return null
        return PendingSteerChip(
            steerId = id,
            text = body,
            status = SteerChipStatus.PENDING,
            createdAt = createdAt ?: Clock.System.now().toEpochMilliseconds(),
        )
    }
}
