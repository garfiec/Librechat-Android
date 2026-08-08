package com.garfiec.librechat.core.data.prefetch

import com.garfiec.librechat.core.common.conversation.OpenConversationRegistry
import com.garfiec.librechat.core.common.identity.AccountState
import com.garfiec.librechat.core.common.identity.ActiveAccountProvider
import com.garfiec.librechat.core.common.identity.currentAccountId
import com.garfiec.librechat.core.common.identity.flatMapAccountOrEmpty
import com.garfiec.librechat.core.data.db.dao.ConversationDao
import com.garfiec.librechat.core.data.db.dao.MessageDao
import com.garfiec.librechat.core.data.db.dao.PrefetchCandidateDetail
import com.garfiec.librechat.core.data.db.dao.PrefetchWatermarkDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/** One conversation's place in the warm set, as the settings readout presents it. */
data class PrefetchConversationStatus(
    val conversationId: String,
    val title: String,
    val pinned: Boolean,
    /** When it was last warmed, or null if it never has been. */
    val warmedAt: Long?,
    /** True when the watermark still matches the server's `updatedAt` — nothing to re-fetch. */
    val isCurrent: Boolean,
)

/**
 * Everything the prefetch readout displays.
 *
 * [warmedCount] counts conversations whose watermark is still current, not merely those that have
 * ever been warmed. The difference is the point: the second number only ever grows and so stops
 * carrying information, while this one falls when threads change and climbs back as the prefetcher
 * catches up, making it an actual liveness signal.
 */
data class PrefetchStatus(
    val conditions: PrefetchConditions,
    val runState: PrefetchRunState,
    val warmedCount: Int,
    val eligibleCount: Int,
    val lastWarmedAt: Long?,
    val warmed: List<PrefetchConversationStatus>,
    val pending: List<PrefetchConversationStatus>,
) {
    companion object {
        /** What the readout shows while no account is resolved — logged out, or still warming. */
        val Empty = PrefetchStatus(
            conditions = PrefetchConditions(
                enabled = false,
                foreground = false,
                networkAllowed = false,
                powerAvailable = false,
                appIdle = false,
                connected = false,
            ),
            runState = PrefetchRunState.Idle,
            warmedCount = 0,
            eligibleCount = 0,
            lastWarmedAt = null,
            warmed = emptyList(),
            pending = emptyList(),
        )
    }
}

/**
 * Assembles the prefetch status readout from the state that already exists — the gate's conditions,
 * the engine's in-flight state, and the watermark table.
 *
 * Nothing here is recorded for the readout's benefit: no counters, no history, no schema. That
 * bounds what it can report (there is no "passes run today", because nothing counts passes) and in
 * exchange it cannot drift from the truth, since every figure is computed from the same rows the
 * engine acts on. Selection reuses [PrefetchPolicy] rather than restating its rules, so what the
 * screen calls pending is by construction what the engine would fetch next.
 */
class PrefetchStatusReporter(
    private val gate: PrefetchGate,
    private val engine: PrefetchEngine,
    private val policy: PrefetchPolicy,
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val watermarkDao: PrefetchWatermarkDao,
    private val openConversationRegistry: OpenConversationRegistry,
    private val activeAccountProvider: ActiveAccountProvider,
) {

    fun status(): Flow<PrefetchStatus> = combine(
        gate.conditions(),
        engine.runState,
        warmSet(),
        activeAccountProvider.state,
    ) { conditions, runState, warmSet, account ->
        warmSet.copy(
            conditions = conditions,
            // Resolved against the active account, because the engine is a singleton and its
            // Stopped verdict persists: an unresolved read would report the account whose server
            // failed under whichever identity is active now.
            runState = runState.stateFor((account as? AccountState.Resolved)?.id?.value),
        )
    }.distinctUntilChanged()

    /**
     * Number of cached message rows for the active account, or zero when no account is resolved.
     *
     * Read on demand rather than observed — see [MessageDao.countForAccount] for why.
     */
    suspend fun cachedMessageCount(): Int =
        activeAccountProvider.currentAccountId()?.let { messageDao.countForAccount(it.value) } ?: 0

    /**
     * The eligible set joined against its watermarks, re-subscribed on every account transition so a
     * switch tears down the previous account's queries rather than reporting its rows under the new
     * identity.
     *
     * Emits a partially-filled [PrefetchStatus] — the conditions and run state it carries are
     * placeholders that [status] replaces, since those come from the gate and the engine rather than
     * the database.
     */
    private fun warmSet(): Flow<PrefetchStatus> =
        activeAccountProvider.flatMapAccountOrEmpty(PrefetchStatus.Empty) { accountId ->
            combine(
                conversationDao.observeRecentForPrefetch(accountId.value, PrefetchPolicy.RECENT_LIMIT),
                conversationDao.observePinnedForPrefetch(accountId.value),
                watermarkDao.observeForAccount(accountId.value),
                openConversationRegistry.openConversationId,
            ) { recent, pinned, watermarks, openConversationId ->
                // The titles the readout needs are dropped by PrefetchPolicy, which works in ids —
                // so run the real selection and map the result back through the detail rows.
                val byId = (recent + pinned).associateBy { it.conversationId }
                val eligible = policy.eligible(
                    recent = recent.map(PrefetchCandidateDetail::toCandidate),
                    pinned = pinned.map(PrefetchCandidateDetail::toCandidate),
                )
                val warmedAtById = watermarks.associate { it.conversationId to it.warmedAt }
                // Order matters and is preserved: selectWork returns the work in the order the
                // engine will actually fetch it — pinned first, then most recent. A pass routinely
                // ends early when the gate closes, so a list sorted any other way shows the rows
                // that get dropped above the ones that get warmed.
                val work = policy.selectWork(
                    eligible = eligible,
                    watermarks = watermarks.associate { it.conversationId to it.warmedConversationUpdatedAt },
                    openConversationId = openConversationId,
                )
                val staleIds = work.mapTo(mutableSetOf()) { it.conversationId }

                fun rowFor(conversationId: String): PrefetchConversationStatus? {
                    val detail = byId[conversationId] ?: return null
                    return PrefetchConversationStatus(
                        conversationId = detail.conversationId,
                        title = detail.title,
                        pinned = detail.pinned,
                        warmedAt = warmedAtById[detail.conversationId],
                        isCurrent = detail.conversationId !in staleIds,
                    )
                }

                val current = eligible.mapNotNull { candidate ->
                    rowFor(candidate.conversationId)?.takeIf { it.isCurrent }
                }
                val stale = work.mapNotNull { rowFor(it.conversationId) }

                PrefetchStatus.Empty.copy(
                    warmedCount = current.size,
                    eligibleCount = current.size + stale.size,
                    // Across the whole table, not just the eligible set: this answers "when did this
                    // last do anything", and a conversation that has since aged out of the warm set
                    // was still the last thing warmed.
                    lastWarmedAt = watermarks.maxOfOrNull { it.warmedAt },
                    warmed = current.sortedByDescending { it.warmedAt ?: 0L },
                    pending = stale,
                )
            }
        }
}
