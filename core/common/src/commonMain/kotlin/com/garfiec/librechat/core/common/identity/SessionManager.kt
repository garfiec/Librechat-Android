package com.garfiec.librechat.core.common.identity

import com.garfiec.librechat.core.common.identity.AccountState.Resolved
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * App-singleton **sole owner** of account-[Session] transitions. It is the only collector of [ActiveAccountProvider.state] and the
 * only mutator of [current]; every transition runs under [mutex] so `cancelAndJoin(old) → publish(new)`
 * is atomic and a fast re-login can't form B's session before A's has fully torn down.
 *
 * The session forms **only** on `Resolved(id != null)` — the non-null gate: both [Warming]
 * (boot sentinel) and `Resolved(null)` (logged-out) map to "no session". So a session can never form
 * under an unknown or logged-out identity, and a flip to either tears the current one down.
 *
 * Nothing consumes [current] yet: the `SessionWriter`/`AccountScopedDb` facade (the object that
 * actually launches writes in [Session.scope]) is deferred.
 *
 * **Single teardown owner:** the imperative logout path drives [endCurrentSession] (so it can
 * sequence the DELETEs after the join) while this reactive collector handles non-logout flips. The
 * collector also reacts to logout's subsequent flip-to-null, but that reconciles to "no session" as a
 * no-op, so the two paths don't double-tear-down; logout must route through [endCurrentSession] rather
 * than running its own parallel teardown.
 */
class SessionManager(
    activeAccountProvider: ActiveAccountProvider,
    private val appScope: CoroutineScope,
    private val teardownTimeoutMs: Long = DEFAULT_TEARDOWN_TIMEOUT_MS,
) {

    private val mutex = Mutex()
    private val _current = MutableStateFlow<Session?>(null)

    /** The live session, or `null` when warming / logged out. */
    val current: StateFlow<Session?> = _current.asStateFlow()

    init {
        appScope.launch {
            activeAccountProvider.state.collect { state ->
                reconcile((state as? Resolved)?.id)
            }
        }
    }

    /**
     * Brings [current] in line with [targetId] (the live resolved id, or `null` for warming/logged-out).
     * No-op when already aligned (same id, or both null) so a duplicate emission doesn't needlessly churn
     * the session. Holds [mutex] across the whole `cancelAndJoin(old) → publish(new)` so transitions
     * can't interleave.
     */
    private suspend fun reconcile(targetId: AccountId?) {
        mutex.withLock {
            val existing = _current.value
            if (existing?.accountId == targetId) return@withLock
            existing?.end(teardownTimeoutMs)
            _current.value = targetId?.let { Session(it, appScope) }
        }
    }

    /**
     * Explicitly tears the current session down and waits for it (logout / account-remove). Funnels
     * through the same [mutex] as the reactive collector so the two owners can't race. Returns once the
     * session scope has joined (or the teardown timeout elapsed); [current] is left `null`.
     */
    suspend fun endCurrentSession() {
        mutex.withLock {
            _current.value?.end(teardownTimeoutMs)
            _current.value = null
        }
    }

    private companion object {
        const val DEFAULT_TEARDOWN_TIMEOUT_MS = 3_000L
    }
}
