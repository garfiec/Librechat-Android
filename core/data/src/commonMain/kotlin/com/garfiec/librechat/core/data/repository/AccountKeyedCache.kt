package com.garfiec.librechat.core.data.repository

import com.garfiec.librechat.core.common.identity.ActiveAccountProvider
import com.garfiec.librechat.core.common.identity.currentAccountId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A single-slot in-memory cache keyed on the active account. Serving the cached value is gated on the
 * key matching the *currently* active account, so account B can never read account A's cached value —
 * for endpoints with no Room/accountId scoping (agents, presets) this is the only isolation tier.
 *
 * The account key is read **under the lock** at serve time, not captured by the caller beforehand:
 * capturing earlier opens a TOCTOU where a coroutine that snapshotted account A before an identity flip
 * is served A's value while B is now active. Read-time keying also closes the window an async
 * clear-on-identity-change collector would leave open: a collector dispatched on a separate coroutine
 * has no happens-before vs the next read, so B's read could beat the clear.
 */
class AccountKeyedCache<T>(private val activeAccountProvider: ActiveAccountProvider) {

    private val mutex = Mutex()
    private var value: T? = null
    private var cachedFor: String? = null

    /** Returns the value for the active account, fetching + caching it on a cold/foreign-key miss. */
    suspend fun getOrFetch(fetch: suspend () -> T): T = mutex.withLock {
        val account = activeAccountProvider.currentAccountId()?.value
        value?.takeIf { cachedFor == account }?.let { return it }
        val fetched = fetch()
        value = fetched
        cachedFor = account
        fetched
    }

    /** Reads the cached value for the active account without fetching; `null` on cold/foreign-key miss. */
    suspend fun <R> peek(block: (T) -> R?): R? = mutex.withLock {
        val account = activeAccountProvider.currentAccountId()?.value
        value?.takeIf { cachedFor == account }?.let(block)
    }

    suspend fun invalidate() = mutex.withLock { value = null }
}
