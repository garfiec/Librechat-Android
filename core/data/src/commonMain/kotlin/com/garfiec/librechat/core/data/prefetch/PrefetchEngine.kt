package com.garfiec.librechat.core.data.prefetch

import com.garfiec.librechat.core.common.conversation.OpenConversationRegistry
import com.garfiec.librechat.core.common.identity.AccountId
import com.garfiec.librechat.core.common.network.PrefetchMarker
import com.garfiec.librechat.core.common.result.ApiException
import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.data.db.dao.ConversationDao
import com.garfiec.librechat.core.data.db.dao.MessageDao
import com.garfiec.librechat.core.data.db.dao.PrefetchCandidate
import com.garfiec.librechat.core.data.db.dao.PrefetchWatermarkDao
import com.garfiec.librechat.core.data.db.entity.PrefetchWatermarkEntity
import com.garfiec.librechat.core.data.repository.AgentRepository
import com.garfiec.librechat.core.data.repository.ConfigRepository
import com.garfiec.librechat.core.data.repository.ConversationRepository
import com.garfiec.librechat.core.data.repository.MessageRepository
import com.garfiec.librechat.core.logging.Diag
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Warms the cache for the conversations the user is likeliest to open next, one request at a time,
 * only while [PrefetchGate] is open.
 *
 * Everything here is shaped by one rule: this work is optional, so it yields to anything that isn't.
 * The gate cancels it rather than it polling for permission; it never runs two requests at once; and
 * it paces itself against the server's own observed latency rather than a fixed interval, so a slow
 * or loaded server automatically gets asked less often.
 *
 * A pass runs in three stages, and the order is load-bearing:
 *
 * 1. **Refresh the conversation list.** Freshness is decided by comparing each conversation's
 *    `updatedAt` against the watermark from its last warm — and that `updatedAt` is read from Room.
 *    Without syncing the list first the engine compares watermarks against the same stale timestamps
 *    it wrote them from, concludes nothing has changed, and never warms anything again.
 * 2. **Warm messages** for whatever that reveals as stale.
 * 3. **Prune** message rows for conversations that have aged out of the warm set.
 */
class PrefetchEngine(
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val watermarkDao: PrefetchWatermarkDao,
    private val messageRepository: MessageRepository,
    private val conversationRepository: ConversationRepository,
    private val configRepository: ConfigRepository,
    private val agentRepository: AgentRepository,
    private val policy: PrefetchPolicy,
    private val openConversationRegistry: OpenConversationRegistry,
    private val ioDispatcher: CoroutineDispatcher,
    // Defaulted rather than injected: both are test seams, and a bare function type is not something
    // Koin can resolve — supplying them from the module would need `Function0` whitelisted in the
    // graph verification, which would then stop catching genuinely unresolvable dependencies.
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {

    /**
     * The account whose pass gave up, if any.
     *
     * Keyed by account rather than a plain flag because this engine is a singleton that outlives any
     * one session: keyed, a switch or re-login starts clean, while repeated gate flips within one
     * session do not keep retrying a server that has already failed three times in a row.
     */
    private var trippedForAccountId: String? = null

    /** Accounts whose slow-moving reference data has been warmed once this process. */
    private val ancillaryWarmed = mutableSetOf<String>()

    suspend fun run(accountId: AccountId) {
        if (trippedForAccountId == accountId.value) return

        // Everything below is marked, so none of these requests count as user activity. Without it
        // the engine's own first request closes the gate that permits it and the pass deadlocks —
        // silently, since a closed gate is indistinguishable from a busy user.
        withContext(PrefetchMarker) {
            val pass = Pass(accountId)

            if (!pass.warmConversationList()) return@withContext
            val eligible = pass.eligible()
            if (!pass.warmMessages(eligible)) return@withContext
            pass.warmAncillary()
            pass.prune(eligible)
        }
    }

    /**
     * One pass's mutable bookkeeping. Held here rather than on the engine so a cancelled pass leaves
     * nothing behind — only the breaker and the ancillary set outlive a pass, and both are keyed by
     * account deliberately.
     */
    private inner class Pass(private val accountId: AccountId) {

        private var consecutiveFailures = 0

        /** Returns false when the pass should stop because the breaker tripped. */
        suspend fun warmConversationList(): Boolean {
            var cursor: String? = null
            var page = 0
            do {
                val start = timeSource.markNow()
                val result = conversationRepository.loadNextPage(cursor = cursor)
                val elapsed = start.elapsedNow()

                cursor = when (val outcome = outcomeOf(result)) {
                    is StepOutcome.Ok -> {
                        consecutiveFailures = 0
                        (result as Result.Success).data
                    }
                    is StepOutcome.RateLimited -> {
                        delay(outcome.backoff)
                        return true // Stop paging; messages will still be warmed on stale data.
                    }
                    StepOutcome.Failed -> return !tripBreaker()
                }
                page++
                delay(paceAfter(elapsed))
            } while (cursor != null && page <= EXTRA_LIST_PAGES)
            return true
        }

        suspend fun eligible(): List<PrefetchCandidate> = withContext(ioDispatcher) {
            policy.eligible(
                recent = conversationDao.recentForPrefetch(accountId.value, PrefetchPolicy.RECENT_LIMIT),
                pinned = conversationDao.pinnedForPrefetch(accountId.value),
            )
        }

        /** Returns false when the breaker tripped partway through. */
        suspend fun warmMessages(eligible: List<PrefetchCandidate>): Boolean {
            val watermarks = withContext(ioDispatcher) {
                watermarkDao.allForAccount(accountId.value)
                    .associate { it.conversationId to it.warmedConversationUpdatedAt }
            }
            val work = policy.selectWork(
                eligible = eligible,
                watermarks = watermarks,
                openConversationId = openConversationRegistry.openConversationId.value,
            )
            Diag.d(
                "Prefetch",
                attrs = mapOf("eligible" to eligible.size.toString(), "stale" to work.size.toString()),
            ) { "warming messages" }

            for (candidate in work) {
                val start = timeSource.markNow()
                val result = messageRepository.refreshMessages(
                    candidate.conversationId,
                    originAccount = accountId,
                )
                val elapsed = start.elapsedNow()

                when (val outcome = outcomeOf(result)) {
                    is StepOutcome.Ok -> {
                        consecutiveFailures = 0
                        recordWatermark(candidate)
                    }
                    // Rate limiting means "slower", not "broken", so it deliberately does not count
                    // toward the breaker — the server is answering, it is just asking us to wait.
                    is StepOutcome.RateLimited -> {
                        Diag.d(
                            "Prefetch",
                            attrs = mapOf("backoffMs" to outcome.backoff.inWholeMilliseconds.toString()),
                        ) { "rate limited" }
                        delay(outcome.backoff)
                        continue
                    }
                    StepOutcome.Failed -> if (tripBreaker()) return false
                }
                delay(paceAfter(elapsed))
            }
            return true
        }

        /**
         * Endpoints, models and the agent list: small, slow-moving, and needed by the first screen
         * the user opens. Warmed once per account per process rather than every pass — a pass runs
         * whenever the user goes idle, and re-fetching reference data that rarely changes on each of
         * those would be most of the prefetcher's traffic.
         */
        suspend fun warmAncillary() {
            if (!ancillaryWarmed.add(accountId.value)) return
            runStep { configRepository.fetchEndpoints() }
            runStep { configRepository.fetchModels() }
            runStep { agentRepository.getAgents() }
        }

        /**
         * Drops cached messages for conversations that have fallen out of the warm set and have not
         * been touched in [PRUNE_AGE]. Conversation rows themselves are kept, so the list stays
         * complete and reopening a pruned thread simply fetches it again.
         */
        suspend fun prune(eligible: List<PrefetchCandidate>) = withContext(ioDispatcher) {
            val cutoff = nowMillis() - PRUNE_AGE.inWholeMilliseconds
            val stale = conversationDao.conversationIdsOlderThan(accountId.value, cutoff)
            val protectedIds = policy.protectedFromPruning(
                eligible = eligible,
                openConversationId = openConversationRegistry.openConversationId.value,
            )
            val prunable = stale.filterNot { it in protectedIds }
            if (prunable.isEmpty()) return@withContext

            // Chunked because SQLite binds at most ~999 variables per statement, and the first prune
            // on a long-lived install is exactly where that limit is met.
            prunable.chunked(PRUNE_CHUNK).forEach { chunk ->
                withContext(NonCancellable) {
                    messageDao.deleteForConversations(accountId.value, chunk)
                    // Together, always: a watermark left behind reports its conversation as warm, so
                    // the rows just deleted would never be fetched again.
                    watermarkDao.deleteFor(accountId.value, chunk)
                }
            }
            Diag.d("Prefetch", attrs = mapOf("pruned" to prunable.size.toString())) { "pruned stale message cache" }
        }

        private suspend fun recordWatermark(candidate: PrefetchCandidate) {
            // Watermark the value fetched against, not "now": the question this answers is "has the
            // server changed since?", which a wall-clock time cannot.
            withContext(ioDispatcher + NonCancellable) {
                watermarkDao.upsert(
                    PrefetchWatermarkEntity(
                        accountId = accountId.value,
                        conversationId = candidate.conversationId,
                        warmedConversationUpdatedAt = candidate.updatedAt,
                        warmedAt = nowMillis(),
                    ),
                )
            }
        }

        /** Runs a paced, breaker-counted step whose result is not otherwise used. */
        private suspend fun runStep(block: suspend () -> Result<*>) {
            val start = timeSource.markNow()
            val result = block()
            val elapsed = start.elapsedNow()
            when (val outcome = outcomeOf(result)) {
                is StepOutcome.Ok -> consecutiveFailures = 0
                is StepOutcome.RateLimited -> delay(outcome.backoff)
                StepOutcome.Failed -> tripBreaker()
            }
            delay(paceAfter(elapsed))
        }

        /** Counts a failure; returns true once the pass should stop. */
        private fun tripBreaker(): Boolean {
            consecutiveFailures++
            if (consecutiveFailures < MAX_CONSECUTIVE_FAILURES) return false
            // Give up for this account rather than working through the whole list against a server
            // that is plainly not answering.
            trippedForAccountId = accountId.value
            Diag.w("Prefetch") { "prefetch stopped after $MAX_CONSECUTIVE_FAILURES consecutive failures" }
            return true
        }
    }

    private fun outcomeOf(result: Result<*>): StepOutcome = when (result) {
        is Result.Success -> StepOutcome.Ok
        is Result.Loading -> StepOutcome.Failed
        is Result.Error -> {
            val api = result.exception as? ApiException
            if (api?.statusCode == HTTP_TOO_MANY_REQUESTS) {
                // Honour the server's own number when it sent one; otherwise wait a fixed period
                // rather than not at all, since the one thing a 429 rules out is retrying now.
                StepOutcome.RateLimited(api.retryAfterSeconds?.seconds ?: DEFAULT_RATE_LIMIT_BACKOFF)
            } else {
                StepOutcome.Failed
            }
        }
    }

    /**
     * Wait proportionally to how long the last request took, so the prefetcher's share of the server
     * shrinks as the server slows down. Clamped at both ends: a fast local server should not be
     * hammered, and one request that burns the full 30s timeout should not stall the pass for two
     * and a half minutes.
     */
    private fun paceAfter(elapsed: Duration): Duration =
        (elapsed * PACE_FACTOR).coerceIn(MIN_PACE, MAX_PACE)

    private sealed interface StepOutcome {
        data object Ok : StepOutcome
        data class RateLimited(val backoff: Duration) : StepOutcome
        data object Failed : StepOutcome
    }

    companion object {
        const val PACE_FACTOR = 5
        const val MAX_CONSECUTIVE_FAILURES = 3

        /** Pages of the conversation list to warm beyond the first. */
        const val EXTRA_LIST_PAGES = 2

        private const val HTTP_TOO_MANY_REQUESTS = 429
        private const val PRUNE_CHUNK = 400
        private val MIN_PACE = 250.milliseconds
        private val MAX_PACE = 30.seconds
        private val DEFAULT_RATE_LIMIT_BACKOFF = 60.seconds
        private val PRUNE_AGE = 90.days
    }
}
