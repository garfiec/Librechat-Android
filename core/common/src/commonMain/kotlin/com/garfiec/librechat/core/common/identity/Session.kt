package com.garfiec.librechat.core.common.identity

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.withTimeout

/**
 * A live account session: an immutable [accountId] captured the moment identity resolved, plus a
 * [SupervisorJob]-backed [scope] that all account-scoped work runs in.
 *
 * Attribution is **structural, not policed per-write**: a write launched in [scope] stamps the
 * session's constant [accountId], so a suspend (upload gate, readiness gate, network PATCH, `gen_title`
 * long-poll) cannot change which account it belongs to — nothing re-reads the active account. Ending
 * the session [cancelAndJoin]s [scope], so a write started under this account is cancelled (best-effort)
 * before the next account forms; any straggler past its last suspension point (Room SQL is
 * non-cancellable mid-statement) is reaped by the orphan sweep, the authoritative net.
 *
 * A [SupervisorJob] (not a plain [Job]) backs the scope so one failing account-scoped write doesn't
 * cancel its siblings; the session job is a child of the application scope's job so it is structured but
 * independently cancellable.
 */
class Session internal constructor(
    val accountId: AccountId,
    parentScope: CoroutineScope,
) {

    private val job = SupervisorJob(parentScope.coroutineContext.job)

    /** Every account-scoped coroutine (writes, queue drain, draft debounce, stream resume) runs here. */
    val scope: CoroutineScope = CoroutineScope(parentScope.coroutineContext + job)

    /**
     * Cancels [scope] and waits for in-flight account-scoped work to unwind, bounded by [timeoutMs].
     * The timeout is swallowed: a write parked on a hung network call must not stall logout — it
     * falls through to the DELETE + orphan sweep rather than hanging. Idempotent.
     */
    internal suspend fun end(timeoutMs: Long) {
        try {
            withTimeout(timeoutMs) { job.cancelAndJoin() }
        } catch (_: TimeoutCancellationException) {
            // Straggler past its last suspension point; the orphan sweep is the authoritative net.
        }
    }
}
