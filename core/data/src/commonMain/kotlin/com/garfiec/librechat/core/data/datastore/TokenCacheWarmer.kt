package com.garfiec.librechat.core.data.datastore

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Drives [CommonTokenDataStore.warmTokenCache] once at startup, off the main thread.
 *
 * Bound `createdAtStart` so it runs without anyone resolving it.
 *
 * **It performs the load; it never waits for someone else to.** A logged-out cold start touches no
 * other entry point on the store — `AccountRegistry` takes its `activeEntry == null` branch and never
 * calls `selectAccount` — so a warmer that merely awaited a seed triggered elsewhere would never
 * complete, and anything gated on it would hang.
 */
class TokenCacheWarmer(
    store: CommonTokenDataStore,
    appScope: CoroutineScope,
) {
    init {
        appScope.launch {
            runCatching { store.warmTokenCache() }
                .onFailure { Logger.w(it) { "Token cache warm failed; falling back to a synchronous load" } }
        }
    }
}
