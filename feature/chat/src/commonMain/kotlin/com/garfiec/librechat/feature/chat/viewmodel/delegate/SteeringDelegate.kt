package com.garfiec.librechat.feature.chat.viewmodel.delegate

import co.touchlab.kermit.Logger
import com.garfiec.librechat.core.common.result.ApiException
import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.data.repository.ChatRepository
import com.garfiec.librechat.core.model.PendingSteer
import com.garfiec.librechat.core.model.request.SteerCancelRequest
import com.garfiec.librechat.core.model.request.SteerRequest
import com.garfiec.librechat.core.model.steer.parseSteerRejectionCode
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
 * **Nothing here may lose the user's text, duplicate it, or send text they withdrew.** Steering
 * is best-effort by construction: the run can end, pause, or fill its queue between the user
 * hitting send and the request landing. So every failure path re-homes the words into the
 * follow-up queue, whose drain fires them when the run ends. That is also why there is no failed
 * chip state: a queued message the user can already edit, reorder and cancel is a better home for
 * text than a dead chip they would have to nurse back to life.
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
     * Queues a message WITHOUT kicking the drain, so it stays put until [pauseQueue] can hold it.
     * Only [reclaimParked] uses this, and only because the ordinary [enqueueFollowUp] self-drains
     * the instant the run is over — which is precisely the state a parked claim arrives in.
     */
    private val enqueueParked: (QueuedMessage) -> Unit,
    /**
     * Holds the follow-up queue instead of letting it auto-drain. Used for steers the server
     * PARKED for a run that ended while nobody was attached — see [reclaimParked].
     */
    private val pauseQueue: () -> Unit,
    /** True while the client still believes a run is in flight. */
    private val isStreaming: () -> Boolean,
) {

    /**
     * Every steer this ViewModel has seen, keyed by the id it currently carries (a local
     * placeholder until the 202 mints the server's, then the server's).
     *
     * One record per steer rather than a set of parallel collections: a steer's lifecycle is a
     * single state machine, and splitting it across "has a spec", "was cancelled", "was
     * applied", "was reclaimed", "is still in flight" made every transition four coordinated
     * writes with a window between them. Settled steers stay here as tombstones — see [clear].
     *
     * **Confined to the handle's Main-dispatched scope** (`viewModelScope`), which is what makes
     * a plain map safe: every mutation below runs on one thread, and the only suspension points
     * are the network calls, which resume back on it. Introducing a `withContext` inside this
     * delegate would break that. A `Mutex` is deliberately NOT the answer — [onSteerApplied],
     * [onPendingSteersSynced] and [clear] are called from non-suspend event dispatch, so locking
     * would force them into `launch` and introduce reordering races worse than the one it guards.
     *
     * Never mutate this inside a `handle.update { }` block: that delegates to
     * `MutableStateFlow.update`, a CAS loop that may re-run its transform on contention, which
     * would apply the mutation twice. Mutate here, then publish the derived chips.
     */
    private val records = LinkedHashMap<String, SteerRecord>()

    /**
     * Which TURN the records below belong to. Bumped by [onTurnBoundary], which the stream manager
     * calls when a genuinely new turn starts — NOT on a reconnect, which is the same turn and must
     * keep its records so the sync frame can rejoin them.
     *
     * Records carry the epoch they were minted in, because nothing else identifies the run a steer
     * belongs to: the ack carries no run id, and `isStreaming` is global, so a slow 202 from a
     * finished turn is indistinguishable from one belonging to the turn now streaming. Without
     * this, a queued follow-up draining into a new run makes `isStreaming` true again and the old
     * turn's steer attaches to it, where nothing can ever retire it.
     */
    private var turnEpoch: Int = 0

    private data class SteerRecord(
        val text: String,
        val createdAt: Long,
        val status: Status,
        /** The send spec this steer falls back to. Null for steers only the server reported. */
        val spec: QueuedMessage?,
        /** The turn this steer was sent into; see [turnEpoch]. */
        val turnEpoch: Int,
    ) {
        enum class Status {
            /** The POST is in flight; the key is a client-minted placeholder. */
            SENDING,

            /** The server accepted it (202); awaiting injection. */
            PENDING,

            /** The user withdrew it. Must never be rendered, re-seeded or re-homed. */
            CANCELLED,

            /** Tombstone: the run injected it, so its text is already in the reply. */
            APPLIED,

            /** Tombstone: already re-homed into the follow-up queue. */
            RECLAIMED,
        }

        val isLive: Boolean get() = status == Status.SENDING || status == Status.PENDING
    }

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
        records[localId] = SteerRecord(trimmed, createdAt, SteerRecord.Status.SENDING, fallback, turnEpoch)
        publishChips()

        handle.scope.launch {
            val result = chatRepository.steerChat(SteerRequest(conversationId, trimmed))
            val record = records[localId] ?: return@launch
            // Settled while the POST was in flight — a turn boundary marked it unreachable, or a
            // report already re-homed it. Its text has a home; neither branch below may give it a
            // second one. CANCELLED is deliberately NOT short-circuited: that record still needs
            // its ack to mint the id the withdrawal is posted against.
            if (record.status == SteerRecord.Status.RECLAIMED ||
                record.status == SteerRecord.Status.APPLIED
            ) {
                records.remove(localId)
                publishChips()
                return@launch
            }
            when (result) {
                is Result.Success ->
                    acknowledge(conversationId, localId, record, result.data.steerId)

                is Result.Error -> {
                    Logger.d(result.exception) { "Steer rejected: ${result.message}" }
                    // A steer the user cancelled mid-flight must not come back as a queued
                    // follow-up: they withdrew the words, not just the delivery route.
                    if (record.status == SteerRecord.Status.CANCELLED) {
                        publishChips()
                        return@launch
                    }
                    settle(localId, SteerRecord.Status.RECLAIMED)
                    val code = parseSteerRejectionCode((result.exception as? ApiException)?.body)
                    Logger.d { "Steer degraded to the follow-up queue (code=$code)" }
                    record.spec?.let(enqueueFollowUp)
                }

                is Result.Loading -> settle(localId, SteerRecord.Status.RECLAIMED)
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
        record: SteerRecord,
        serverId: String?,
    ) {
        val wasCancelled = record.status == SteerRecord.Status.CANCELLED
        // A 202 with no id is unusable: it can be neither cancelled nor matched to an applied
        // event, so treat it as un-steered rather than showing a chip that can never resolve.
        if (serverId.isNullOrBlank()) {
            settle(localId, if (wasCancelled) SteerRecord.Status.CANCELLED else SteerRecord.Status.RECLAIMED)
            if (!wasCancelled) record.spec?.let(enqueueFollowUp)
            return
        }

        // Re-key onto the server id, keeping createdAt (display order follows what the user did)
        // and the spec (the config the steer was composed with).
        records.remove(localId)
        val existing = records[serverId]
        if (wasCancelled) {
            records[serverId] = record.copy(status = SteerRecord.Status.CANCELLED)
            publishChips()
            postCancel(conversationId, serverId)
            return
        }
        // The applied event can beat this ack, naming an id the client had not learned yet.
        // Without the tombstone the ack would mint a chip for a steer already in the reply.
        if (existing != null && !existing.isLive) {
            records[serverId] = existing
            publishChips()
            return
        }
        // Either the run is over, or a NEWER turn is running and this ack belongs to a finished
        // one. Both mean no injection is coming and no event will ever retire the chip, so the
        // text is re-homed rather than attached to a run that never accepted it.
        if (!isStreaming() || record.turnEpoch != turnEpoch) {
            records[serverId] = record.copy(status = SteerRecord.Status.RECLAIMED)
            publishChips()
            record.spec?.let(enqueueFollowUp)
            return
        }
        records[serverId] = record.copy(status = SteerRecord.Status.PENDING)
        publishChips()
    }

    /**
     * A steer reached the run and is now part of the reply (`on_steer_applied`).
     *
     * The id is recorded even when no record matches: the event can arrive before this client's
     * own 202, and the tombstone is what stops that ack from re-minting a chip for a steer
     * already in the content.
     */
    fun onSteerApplied(steerId: String) {
        if (steerId.isBlank()) return
        val existing = records[steerId]
        records[steerId] = existing?.copy(status = SteerRecord.Status.APPLIED)
            ?: SteerRecord("", 0L, SteerRecord.Status.APPLIED, spec = null, turnEpoch = turnEpoch)
        evictSettled()
        publishChips()
    }

    /**
     * Replaces the chips with the server's still-queued steers, from a reconnect's
     * `resumeState.pendingSteers`.
     *
     * The server's list is authoritative — it knows what was injected while this client was away
     * — so an empty one correctly clears stale chips. In-flight local records are kept: their own
     * POST has not answered yet, and by definition their ids are not in the server's list.
     *
     * A steer the user cancelled is NOT re-seeded even when the server still lists it: the cancel
     * may simply not have been processed yet, and resurrecting the chip here would let the run's
     * end re-home text the user withdrew.
     */
    fun onPendingSteersSynced(steers: List<PendingSteer>) {
        val reported = steers.mapNotNull { it.toRecordEntry() }
        val reportedIds = reported.map { it.first }.toSet()
        // Drop PENDING records the server no longer lists — they were injected or dropped while
        // this client was away. SENDING records have no server id yet, so they cannot be listed.
        records.entries.removeAll { (id, record) ->
            record.status == SteerRecord.Status.PENDING && id !in reportedIds
        }
        // A steer this client already knows keeps its own record — including the spec it was
        // composed with, which a server report cannot carry. Only genuinely new ones are added.
        reported.forEach { (id, incoming) -> records.getOrPut(id) { incoming } }
        publishChips()
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
     *
     * All three reports can carry the SAME steer — a stopped run reports its list on the ack and
     * again on the aborted final — so ids already settled are skipped rather than queued a second
     * time. A steer the user cancelled is skipped for the same reason it is not re-seeded.
     */
    fun reclaim(steers: List<PendingSteer>): Int = reclaimInto(steers, enqueueFollowUp)

    private fun reclaimInto(steers: List<PendingSteer>, enqueue: (QueuedMessage) -> Unit): Int {
        if (steers.isEmpty()) return 0
        val reported = steers.mapNotNull { it.toRecordEntry() }
        if (reported.isEmpty()) return 0
        val queued = reported.sortedBy { it.second.createdAt }
            .count { (id, incoming) -> rehome(id, records[id] ?: incoming, enqueue) }
        evictSettled()
        publishChips()
        return queued
    }

    /**
     * Converts records the run left behind into queued follow-ups when no server report arrives
     * to reclaim them — a stream that dies on an error carries no `pendingSteers`, but the text
     * of every accepted steer is still held here.
     *
     * Only PENDING records convert: a SENDING record's POST has not answered yet and will
     * re-home its own text through the rejection path, so taking it here as well would send the
     * same message twice.
     */
    fun reclaimLocalChips() {
        records.filterValues { it.status == SteerRecord.Status.PENDING && it.turnEpoch == turnEpoch }
            .entries
            .sortedBy { it.value.createdAt }
            .forEach { (id, record) -> rehome(id, record, enqueueFollowUp) }
        evictSettled()
        publishChips()
    }

    /** Re-homes one steer's text into the follow-up queue, exactly once. Returns true if queued. */
    private fun rehome(id: String, record: SteerRecord, enqueue: (QueuedMessage) -> Unit): Boolean {
        // Already settled: injected, withdrawn, or re-homed by an earlier report.
        if (records[id]?.isLive == false) return false
        records[id] = record.copy(status = SteerRecord.Status.RECLAIMED)
        val spec = record.spec ?: buildFollowUp(record.text) ?: return false
        enqueue(spec)
        return true
    }

    /** Withdraws a queued steer. Optimistic — the row goes immediately, the POST just confirms. */
    fun cancel(steerId: String) {
        val record = records[steerId]?.takeIf { it.isLive } ?: return
        // Recorded BEFORE the conversation lookup below: on the no-conversation path the chip is
        // already gone from view, and leaving the withdrawal unrecorded would let the steer's own
        // ack resurrect it.
        records[steerId] = record.copy(status = SteerRecord.Status.CANCELLED)
        publishChips()
        val conversationId = handle.state.conversationId ?: return
        // A SENDING record has no server id to cancel yet — its own ack posts the cancel once the
        // id exists (see acknowledge).
        if (record.status == SteerRecord.Status.PENDING) postCancel(conversationId, steerId)
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
     * A genuinely new turn is starting. Called from the stream manager's `beginStreaming` and
     * `reset` — deliberately NOT from `resumeStream`, which re-enters the SAME turn and whose
     * records must survive so the reconnect's sync frame can rejoin them by server id.
     *
     * PENDING records from that turn are SETTLED here, not re-homed. `endStream` already
     * re-homed them if it was going to; it deliberately does not on a clean `Finalized`, where
     * the frame's own list is authoritative and converting again would double-send a steer whose
     * applied event this client merely missed. Anything still live at this point is unreachable,
     * so settling is the only safe move — re-homing would duplicate.
     *
     * SENDING records are left alone: their POST has not answered yet, and its continuation is
     * what re-homes their text (the epoch stamp is what tells it the turn has moved on).
     */
    fun onTurnBoundary() {
        records.entries
            .filter { it.value.status == SteerRecord.Status.PENDING && it.value.turnEpoch == turnEpoch }
            .forEach { it.setValue(it.value.copy(status = SteerRecord.Status.RECLAIMED)) }
        turnEpoch++
        evictSettled()
        publishChips()
    }

    /**
     * Claims steers the server PARKED because the run ended with no subscriber attached, and
     * holds them instead of firing them.
     *
     * Separate from [reclaim] because of where these arrive: `/chat/status` hands them over on
     * conversation OPEN, in a fresh ViewModel whose queue is empty and unpaused — so the ordinary
     * enqueue would auto-drain and send, unprompted, a message the user last saw parked behind a
     * Stop, possibly from a much earlier session. Pausing surfaces it as "Send queued" instead,
     * which is recoverable and never surprises.
     *
     * The claim therefore goes through [enqueueParked], which does NOT kick the drain, and the
     * hold is applied once something is actually queued. Both halves are load-bearing and each
     * defeated the original "claim, then pause": the ordinary enqueue self-drains, so the message
     * was already sent by the time the pause ran; and a queue with nothing in it cannot be paused
     * anyway, so that pause was a no-op regardless. Together they auto-sent a parked steer on
     * conversation open — verified on device, not hypothetical.
     */
    fun reclaimParked(steers: List<PendingSteer>) {
        if (reclaimInto(steers, enqueueParked) > 0) pauseQueue()
    }

    /**
     * Session boundary: stops rendering the current run's chips.
     *
     * This is a DISPLAY boundary, not a memory one. Chips describe one run's injection queue, so
     * carrying them into the next stream would show pending work against a run that never
     * accepted it — but the records behind them are kept, with their specs:
     *
     * - a mid-run reconnect goes through here ([resumeStream] starts a new session), and the sync
     *   frame then re-seeds the same steers by server id. Dropping the records would strand their
     *   specs, and the re-homed message would be rebuilt from whatever the composer holds by then
     *   — the exact failure the specs exist to prevent;
     * - a settled record is the only thing that stops a late ack, or a second server report, from
     *   re-homing text that is already in the reply or already queued;
     * - a SENDING record's POST outlives the boundary and needs its spec to degrade.
     */
    fun clear() {
        evictSettled()
        if (handle.state.steer.pendingSteers.isEmpty()) return
        handle.update { steer = SteerState() }
    }

    /** Marks a record settled without re-homing anything. */
    private fun settle(id: String, status: SteerRecord.Status) {
        records[id] = records[id]?.copy(status = status) ?: return
        evictSettled()
        publishChips()
    }

    /**
     * Bounds the tombstones. Live records are never evicted — a SENDING record's ack and a
     * PENDING record's injection are both still owed — so only settled ones age out, oldest
     * first, well past any plausible in-flight ack or duplicate-report window.
     */
    private fun evictSettled() {
        // A live record from a previous turn is unreachable — nothing will ack, inject or report
        // it — so it is evictable too, or the map grows for the ViewModel's lifetime.
        val settled = records.entries.filter { !it.value.isLive || it.value.turnEpoch != turnEpoch }
        if (settled.size <= MAX_SETTLED_RECORDS) return
        settled.take(settled.size - MAX_SETTLED_RECORDS).forEach { records.remove(it.key) }
    }

    /** Publishes the live records as the rendered chip list. */
    private fun publishChips() {
        val chips = records.entries
            .filter { it.value.isLive && it.value.turnEpoch == turnEpoch }
            .map { (id, record) ->
                PendingSteerChip(
                    steerId = id,
                    text = record.text,
                    status = if (record.status == SteerRecord.Status.SENDING) {
                        SteerChipStatus.SENDING
                    } else {
                        SteerChipStatus.PENDING
                    },
                    createdAt = record.createdAt,
                )
            }
            .sortedBy { it.createdAt }
        if (handle.state.steer.pendingSteers == chips) return
        handle.update { steer = steer.copy(pendingSteers = chips) }
    }

    /** Server records with no id or no text can be neither cancelled nor replayed; drop them. */
    private fun PendingSteer.toRecordEntry(): Pair<String, SteerRecord>? {
        val id = steerId?.takeIf { it.isNotBlank() } ?: return null
        val body = text?.takeIf { it.isNotBlank() } ?: return null
        return id to SteerRecord(
            text = body,
            createdAt = createdAt ?: Clock.System.now().toEpochMilliseconds(),
            status = SteerRecord.Status.PENDING,
            spec = null,
            turnEpoch = turnEpoch,
        )
    }

    private companion object {
        /** Retention for settled records; well past any plausible in-flight ack window. */
        const val MAX_SETTLED_RECORDS = 32
    }
}
