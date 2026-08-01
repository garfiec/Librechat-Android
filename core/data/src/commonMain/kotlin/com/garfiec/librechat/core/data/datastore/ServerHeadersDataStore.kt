package com.garfiec.librechat.core.data.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import co.touchlab.kermit.Logger
import com.garfiec.librechat.core.common.identity.deriveServerId
import com.garfiec.librechat.core.network.client.CustomHeaderRules
import com.garfiec.librechat.core.network.client.ServerHeadersProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.concurrent.Volatile

/**
 * Per-server gateway headers (issue #287), persisted as `srv:<serverId>:custom_headers` in the shared
 * preference store — the same server-scoped namespace as [ConfigCacheDataStore].
 *
 * **Server-scoped, not account-scoped**, and that is forced rather than chosen: the credential is
 * needed on the very first request to a protected deployment, which happens before any account
 * exists. It also means nothing sweeps it — `removeAllForAccount` is `acct:`-prefixed only, so
 * logging out keeps the headers, which is required, since they are what lets you log back *in*.
 *
 * **Stored in plaintext.** The realistic exfiltration path for app-private preferences is an ADB
 * backup, which `android:allowBackup="false"` already closes; on iOS the app container is sandboxed.
 * Against that, `EncryptedSharedPreferences` would add a wipe-and-rebuild failure mode on OEMs with
 * broken Keystores (see `TokenDataStore`) — and losing this particular value doesn't degrade to a
 * re-login prompt, it degrades to a server the user can no longer reach at all.
 *
 * The in-memory map is **Flow-backed, not read once**: `ServerUrlViewModel` writes headers and probes
 * the server back-to-back, so a one-shot warm read would make the first Connect after entering a
 * token fail and the second succeed.
 */
class ServerHeadersDataStore(
    private val dataStore: DataStore<Preferences>,
    private val json: Json,
    appScope: CoroutineScope,
    ioDispatcher: CoroutineDispatcher,
) : ServerHeadersProvider {

    // Completes once the persisted headers have resolved (success, failure, or cancellation), so a
    // cold-start request never builds itself against the empty pre-warm-up map. An access gateway
    // answers a credential-less request with a redirect to its own login page, not a retryable error,
    // so "we'll catch it on the retry" is not available to us here.
    private val warmedUp = CompletableDeferred<Unit>()

    @Volatile
    private var byServerId: Map<String, Map<String, String>> = emptyMap()

    private val writeMutex = Mutex()

    init {
        appScope.launch(ioDispatcher) {
            try {
                dataStore.data.collect { prefs ->
                    byServerId = parse(prefs)
                    warmedUp.complete(Unit)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Leave the map empty: a server with no gateway in front of it still works, and one
                // with a gateway surfaces the same actionable failure as an unconfigured server.
                Logger.w(e) { "Failed to load custom server headers" }
            } finally {
                // Also on cancellation — an awaiter must never hang on a scope that went away.
                warmedUp.complete(Unit)
            }
        }
    }

    override suspend fun awaitWarm() {
        warmedUp.await()
    }

    override fun headersFor(baseUrl: String): Map<String, String> =
        serverIdOf(baseUrl)?.let { byServerId[it] }.orEmpty()

    /** Headers currently configured for [serverUrl], after the store has warmed up. For the UI. */
    suspend fun headersForServer(serverUrl: String): Map<String, String> {
        warmedUp.await()
        return headersFor(serverUrl)
    }

    /**
     * Persist [headers] for [serverUrl], replacing whatever was there. An empty map removes the entry
     * entirely rather than storing `{}`.
     *
     * Refreshes the in-memory map before returning instead of waiting for the collector to see the
     * write, so the caller's very next request carries the new headers. `ServerUrlViewModel` saves and
     * then immediately probes the server, and that probe is the whole point of saving.
     *
     * @return false when [serverUrl] yields no server id and nothing was written. Callers must not
     * report success on a false: a UI that confirms "saved" and clears its dirty flag leaves the user
     * believing a credential is stored that never reached disk, with no way to retry.
     */
    suspend fun setHeaders(serverUrl: String, headers: Map<String, String>): Boolean {
        val serverId = serverIdOf(serverUrl) ?: return false
        val sanitized = CustomHeaderRules.sanitize(headers)
        val key = serverScopedKey(serverId, CUSTOM_HEADERS)
        writeMutex.withLock {
            dataStore.edit { prefs ->
                if (sanitized.isEmpty()) prefs.remove(key) else prefs[key] = json.encodeToString(SERIALIZER, sanitized)
            }
            // Re-parse the whole store rather than patching one entry: a concurrent write to a
            // different server would otherwise be clobbered by a stale read-modify-write.
            byServerId = parse(dataStore.data.first())
        }
        return true
    }

    /**
     * The server id for [rawUrl], or null when it isn't a usable server URL.
     *
     * Always derived, never string-compared: `AccountSwitcher.beginAdd` pins its URL with
     * `trimTrailingSlash()` rather than `normalizeServerUrl`, so the strings genuinely differ between
     * call sites while the deployment is the same.
     *
     * Both `normalizeServerUrl` and `ServerId` reject blank/schemeless input by throwing, and blank
     * base URLs are entirely routine on this path — cold start before warm-up, and
     * `ServerUrlViewModel` setting `setServerUrl("")` after every failed probe. Swallow rather than
     * propagate: this runs on the request path, where a throw would surface as a network failure.
     */
    private fun serverIdOf(rawUrl: String): String? {
        if (rawUrl.isBlank()) return null
        return runCatching { deriveServerId(rawUrl).value }.getOrNull()
    }

    private fun parse(prefs: Preferences): Map<String, Map<String, String>> =
        prefs.asMap().entries.mapNotNull { (key, value) ->
            val name = key.name
            if (!name.startsWith(PREFIX) || !name.endsWith(SUFFIX)) return@mapNotNull null
            val serverId = name.removePrefix(PREFIX).removeSuffix(SUFFIX)
            if (serverId.isEmpty()) return@mapNotNull null
            val raw = value as? String ?: return@mapNotNull null
            val decoded = runCatching { json.decodeFromString(SERIALIZER, raw) }.getOrNull() ?: return@mapNotNull null
            // Sanitise on the way out of storage too, so a pair written by an older build — or one
            // whose name a later release added to RESERVED_NAMES — can't reach the wire.
            val sanitized = CustomHeaderRules.sanitize(decoded)
            if (sanitized.isEmpty()) null else serverId to sanitized
        }.toMap()

    private companion object {
        const val CUSTOM_HEADERS = "custom_headers"
        val PREFIX = serverScopedName("", "").removeSuffix(":")
        val SUFFIX = ":$CUSTOM_HEADERS"
        val SERIALIZER = MapSerializer(String.serializer(), String.serializer())
    }
}
