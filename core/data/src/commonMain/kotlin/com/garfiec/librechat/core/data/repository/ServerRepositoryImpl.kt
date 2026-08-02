package com.garfiec.librechat.core.data.repository

import co.touchlab.kermit.Logger
import com.garfiec.librechat.core.common.identity.deriveServerId
import com.garfiec.librechat.core.common.identity.normalizeServerUrl
import com.garfiec.librechat.core.data.db.dao.ServerDao
import com.garfiec.librechat.core.data.db.entity.ServerEntity
import com.garfiec.librechat.core.network.client.CustomHeaderRules
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.concurrent.Volatile

/**
 * Room-backed [ServerRepository]. Headers are stored in plaintext; see `core/data/CLAUDE.md`
 * ("Per-server gateway headers") for that threat model and the rest of the rationale.
 *
 * **This class must stay the table's only writer.** Every change goes through [setHeaders], which
 * patches the in-memory map under the same lock that performs the write. A second writer — or a Room
 * `Flow` observing the table — reintroduces a stale-snapshot emission that can arrive after a save
 * and revert a credential the user just typed.
 *
 * **Two read paths, deliberately.** [headersFor] / [awaitWarm] serve the request pipeline from a
 * snapshot seeded once at startup and never re-read; [headersForServer] serves the editors and reads
 * through to the table every time. **Don't merge them:** no single retry policy serves both —
 * bounded, a transient failure disables headers for the rest of the process; unbounded, an
 * unreadable database queues every request behind the same failing query. Split, the request path
 * never retries, and opening an editor is what heals a store that failed at startup.
 */
class ServerRepositoryImpl(
    private val serverDao: ServerDao,
    private val json: Json,
    appScope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
) : ServerRepository {

    // Completes once the persisted headers have resolved (success, failure, or cancellation), so a
    // cold-start request never builds itself against the empty pre-warm-up map. An access gateway
    // answers a credential-less request with a redirect to its own login page, not a retryable error,
    // so "we'll catch it on the retry" is not available to us here.
    private val warmedUp = CompletableDeferred<Unit>()

    @Volatile
    private var byServerId: Map<String, Map<String, String>> = emptyMap()

    /**
     * Server ids whose stored value is present but would not decode, so an absence from
     * [byServerId] means "could not read" rather than "none configured" for those alone. A whole
     * unreadable table is [readFailed]; this is the single-corrupt-row case.
     */
    @Volatile
    private var unreadable: Set<String> = emptySet()

    /**
     * Set when the read failed outright, so an empty map means "could not read" rather than "none
     * configured". Requests can only fail-open either way, but the editors must not present an
     * unreadable store as an absent credential.
     */
    @Volatile
    private var readFailed: Boolean = false

    /**
     * Server ids whose current stored value this process wrote itself.
     *
     * A write we performed is knowledge about that server that a later failed read cannot take away
     * — and since this class is the table's only writer, nothing can invalidate it. Without this a
     * store whose reads fail but whose writes land would tell the user their credential saved and
     * then, on the next open, that it could not be read: they re-enter a secret every request is
     * already carrying.
     */
    @Volatile
    private var writtenHere: Set<String> = emptySet()

    private val lock = Mutex()

    init {
        appScope.launch(ioDispatcher) {
            try {
                lock.withLock { seedLocked() }
            } finally {
                // Also on cancellation — an awaiter must never hang on a scope that went away.
                warmedUp.complete(Unit)
            }
        }
    }

    /**
     * Reads the whole table into [byServerId]. Caller must hold [lock] — on the seed, so a
     * [setHeaders] that lands mid-read can't be overwritten by the read it raced; always, because
     * the flags describe the map and a reader that saw one without the other would act on a stale
     * pairing.
     */
    private suspend fun seedLocked() {
        try {
            val decoded = serverDao.getAll().decode()
            byServerId = decoded.headers
            unreadable = decoded.undecodable
            readFailed = false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Fail open, but remember why — see [readFailed].
            readFailed = true
            Logger.w(e) { "Failed to load custom server headers" }
        }
    }

    /**
     * The request path. Waits for the seed and answers from the snapshot — deliberately **without**
     * re-reading, ever.
     *
     * This runs in every request's pre-flight, so a re-read here is either a query per request or a
     * queue of requests behind one failing query, and no retry budget settles that trade: bound it
     * and a transient failure disables headers for the process, unbound it and a dead database
     * stalls every screen. There is no retry policy because the retry lives on
     * [headersForServer] instead — the editor's path, where a user is waiting for the answer and a
     * query is affordable. Opening the editor is therefore what heals a store that failed at
     * startup.
     */
    override suspend fun awaitWarm() {
        warmedUp.await()
    }

    override fun headersFor(baseUrl: String): Map<String, String> =
        serverIdOf(baseUrl)?.let { byServerId[it] }.orEmpty()

    override suspend fun headersForServer(serverUrl: String): Map<String, String>? {
        val serverId = serverIdOf(serverUrl) ?: return emptyMap()
        warmedUp.await()
        // Read through rather than answering from the snapshot: this is the one caller that needs the
        // truth rather than a fast answer, and it is rare enough to pay a query for it.
        withContext(ioDispatcher) { lock.withLock { seedLocked() } }
        // What we wrote outranks a read that failed — see [writtenHere].
        if (serverId in writtenHere) return byServerId[serverId].orEmpty()
        if (readFailed) return null
        return if (serverId in unreadable) null else byServerId[serverId].orEmpty()
    }

    override suspend fun setHeaders(serverUrl: String, headers: Map<String, String>): HeaderWriteResult {
        val serverId = serverIdOf(serverUrl)
            ?: return HeaderWriteResult.Refused(HeaderWriteFailure.NoServer)
        val sanitized = CustomHeaderRules.sanitize(headers)
        // Only an empty *request* is a clear. A non-empty one that sanitises down to nothing is a
        // caller asking to set headers that all turned out to be unsendable — deleting on it would
        // destroy a credential in answer to a call that never asked to. Both editors validate before
        // they get here, so this is a backstop for the callers `sanitize` exists to defend against.
        if (headers.isNotEmpty() && sanitized.isEmpty()) {
            return HeaderWriteResult.Refused(HeaderWriteFailure.NothingUsable)
        }
        // An empty write can only be judged once the seed has been *attempted*: before that, "nothing
        // stored" and "never read" are the same empty map, and only the flags below tell them apart.
        warmedUp.await()
        return withContext(ioDispatcher) {
            lock.withLock {
                // Retry the read before deciding, so a transient failure doesn't leave the user
                // permanently unable to clear a credential through the refusal below.
                if (readFailed) seedLocked()
                if (sanitized.isEmpty() && (readFailed || serverId in unreadable)) {
                    // An empty write is a delete, and this store could not show the caller what it is
                    // about to destroy. The rule lives here rather than in the editors because this is
                    // the only layer that knows the read failed.
                    return@withLock HeaderWriteResult.Refused(HeaderWriteFailure.UnverifiedDelete)
                }
                try {
                    if (sanitized.isEmpty()) {
                        serverDao.deleteById(serverId)
                    } else {
                        serverDao.upsert(ServerEntity(serverId, json.encodeToString(SERIALIZER, sanitized)))
                    }
                    // Patch rather than re-read: this is the table's only writer, so the map after a
                    // committed write is exactly the map plus (or minus) this one entry.
                    byServerId = byServerId.toMutableMap().apply {
                        if (sanitized.isEmpty()) remove(serverId) else put(serverId, sanitized)
                    }
                    // A write proves this row is writable, not that the table is readable, so
                    // [readFailed] deliberately stays set — the retry above is the only thing that
                    // clears it. Every *other* server would otherwise be missing from the map, where
                    // missing is indistinguishable from "none configured". This one server is no
                    // longer in doubt, though, whatever the table's state.
                    unreadable = unreadable - serverId
                    writtenHere = writtenHere + serverId
                    HeaderWriteResult.Saved
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Reported, not thrown: the editors turn a refusal into "couldn't save", which is
                    // the one outcome the user can act on.
                    Logger.w(e) { "Failed to persist custom server headers" }
                    HeaderWriteResult.Refused(HeaderWriteFailure.StorageUnavailable)
                }
            }
        }
    }

    /** The decoded table: usable headers per server, plus the ids whose stored value would not read. */
    private class DecodedRows(
        val headers: Map<String, Map<String, String>>,
        val undecodable: Set<String>,
    )

    private fun List<ServerEntity>.decode(): DecodedRows {
        val headers = mutableMapOf<String, Map<String, String>>()
        val undecodable = mutableSetOf<String>()
        for (row in this) {
            val decoded = runCatching { json.decodeFromString(SERIALIZER, row.customHeaders) }.getOrNull()
            if (decoded == null) {
                // Tracked, not skipped. A row that won't decode is not a server without headers:
                // reporting it as "none configured" shows an editor the user reads as their
                // credential having vanished, and the next save from that editor deletes the row
                // that could still have been recovered.
                undecodable += row.serverId
                continue
            }
            // Sanitise on the way out of storage too, so a pair written by an older build — or one
            // whose name a later release added to RESERVED_NAMES — can't reach the wire.
            val sanitized = CustomHeaderRules.sanitize(decoded)
            when {
                sanitized.isNotEmpty() -> headers[row.serverId] = sanitized
                // Decoded, but every pair had to be dropped. The row still holds something the user
                // put there, so calling it "none configured" is the same lie as skipping a row that
                // wouldn't decode at all — and it invites the save that deletes it.
                decoded.isNotEmpty() -> undecodable += row.serverId
                // A stored empty map. [setHeaders] deletes the row rather than writing one, so this
                // can only come from an older build, and there is nothing here to lose.
                else -> Unit
            }
        }
        return DecodedRows(headers, undecodable)
    }

    /**
     * The server id for [rawUrl], or null when it isn't a usable server URL.
     *
     * Always derived, never string-compared: `AccountSwitcher.beginAdd` pins its URL with
     * `trimTrailingSlash()` rather than [normalizeServerUrl], so the strings genuinely differ between
     * call sites while the deployment is the same.
     *
     * [deriveServerId] throws on blank/schemeless input, and blank base URLs are routine here (cold
     * start, and `ServerUrlViewModel` clearing the URL after a failed probe). Swallow rather than
     * propagate: this runs on the request path, where a throw surfaces as a network failure.
     */
    private fun serverIdOf(rawUrl: String): String? {
        if (rawUrl.isBlank()) return null
        return runCatching { deriveServerId(rawUrl).value }.getOrNull()
    }

    private companion object {
        val SERIALIZER = MapSerializer(String.serializer(), String.serializer())
    }
}
