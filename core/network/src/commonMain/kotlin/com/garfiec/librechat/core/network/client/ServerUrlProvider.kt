package com.garfiec.librechat.core.network.client

interface ServerUrlProvider {
    fun getBaseUrl(): String

    /**
     * Suspends until the server URL has been resolved (e.g. the implementation's async
     * warm-up of the persisted value finishes), then returns it. Startup-path coroutines
     * that must not race the warm-up window — where [getBaseUrl] can transiently be empty —
     * should await this instead of reading [getBaseUrl] directly. Defaults to [getBaseUrl]
     * for implementations that resolve eagerly.
     */
    suspend fun awaitBaseUrl(): String = getBaseUrl()
}
