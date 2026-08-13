package com.garfiec.librechat.core.data.datastore

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.url
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * The one in-memory [CommonTokenDataStore] the datastore tests share: read-through/write-through
 * over a map, with no `EncryptedSharedPreferences`. Add to this rather than re-rolling a local copy
 * — a per-suite fake also re-rolls the `Dispatchers.Unconfined` rule below, whose omission surfaces
 * only as a flaking assertion.
 */
internal class FakeTokenStore(
    refreshClient: Lazy<HttpClient>,
    seed: Map<String, String> = emptyMap(),
    /**
     * Warms the cache during construction, which is what production's `TokenCacheWarmer` does a moment
     * after `startKoin`. Defaults to `true` so every suite sees a seeded store. Pass `false` to
     * exercise the unwarmed window — the state a real store is in between construction and the warm
     * landing.
     */
    warmEagerly: Boolean = true,
) : CommonTokenDataStore(refreshClient, Dispatchers.Unconfined) {

    val store = seed.toMutableMap()

    /** Access tokens written, paired with the refresh token stored alongside at the time. */
    val writeLog = mutableListOf<Pair<String, String>>()

    /** Logical clears, counted off the access-key removal so a pair teardown counts once. */
    var removeCount = 0
        private set

    /** Every [readValue], so a test can assert the store touched storage zero times before its warm. */
    var readCount = 0
        private set

    init {
        if (warmEagerly) runBlocking { warmTokenCache() }
    }

    /** The bare (no-account-resolved) slots, which is what a store with no active account uses. */
    fun persistedAccess(): String? = store[KEY_ACCESS_TOKEN]

    fun persistedRefresh(): String? = store[KEY_REFRESH_TOKEN]

    override fun readValue(key: String): String? {
        readCount++
        return store[key]
    }

    override fun writeValue(key: String, value: String) {
        store[key] = value
        if (key == KEY_ACCESS_TOKEN) writeLog += value to (store[KEY_REFRESH_TOKEN] ?: "")
    }

    override fun writeValues(values: Map<String, String>) {
        values.forEach { (key, value) -> writeValue(key, value) }
    }

    override fun removeValue(key: String) {
        store.remove(key)
        if (key == KEY_ACCESS_TOKEN) removeCount++
    }

    override fun onKeystoreCorruption() = Unit
}

/** Seeds the bare keys, the shape a store has before any account is resolved. */
internal fun bareSeed(
    access: String? = "initial-access",
    refresh: String? = "initial-refresh",
): Map<String, String> = buildMap {
    access?.let { put(CommonTokenDataStore.KEY_ACCESS_TOKEN, it) }
    refresh?.let { put(CommonTokenDataStore.KEY_REFRESH_TOKEN, it) }
}

internal fun accessKeyOf(account: String) = "acct:$account:access_token"

internal fun refreshKeyOf(account: String) = "acct:$account:refresh_token"

internal const val ACTIVE_ACCOUNT_KEY = "active_account_id"

/**
 * A [MockEngine] pinned to [Dispatchers.Unconfined].
 *
 * MockEngine defaults its dispatcher to `Dispatchers.IO`, which hops the handler — and everything
 * downstream of it — onto a real thread pool outside the test scheduler's virtual clock.
 * `yield()`/`advanceUntilIdle()` cannot wait for real-thread work, so assertions race it. Pinning to
 * Unconfined keeps request handling synchronous with the test dispatcher.
 */
internal fun unconfinedMockEngine(handler: MockRequestHandler): MockEngine = MockEngine(
    MockEngineConfig().apply {
        requestHandlers.add(handler)
        dispatcher = Dispatchers.Unconfined
    },
)

internal fun refreshClientOf(engine: MockEngine, baseUrl: String = TEST_SERVER): Lazy<HttpClient> = lazy {
    HttpClient(engine) {
        install(ContentNegotiation) { json() }
        defaultRequest {
            url(baseUrl)
            contentType(ContentType.Application.Json)
        }
    }
}

internal const val TEST_SERVER = "https://chat.example.com"

internal val jsonResponseHeaders = headersOf("Content-Type", ContentType.Application.Json.toString())

internal fun refreshResponseBody(token: String): String = """{"token":"$token"}"""
