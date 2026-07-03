package com.garfiec.librechat.core.network.client

import com.garfiec.librechat.core.common.identity.ActiveAccountProvider
import com.garfiec.librechat.core.common.identity.currentAccountId
import io.ktor.util.AttributeKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * The consistent `(baseUrl, accountId, bearer)` triple a single request/stream is bound to for its
 * whole life. Captured once at the [SwitchGate] barrier and stored in the request attributes under
 * [RequestIdentityKey]; every URL / bearer / host read downstream reads *this* instead of the live
 * providers, so an account switch that flips the providers mid-request can't tear the triple apart.
 *
 * [isPending] marks a snapshot minted from a [PendingRequestIdentity] (an add-account flow
 * authenticating against a server that is not yet active). A pending request has no keyed account to
 * refresh, and its failures belong to the add flow, not the app: the 401-retry passes it through
 * without refreshing anything or emitting the global session-expired signal.
 */
data class RequestIdentity(
    val baseUrl: String,
    val accountId: String?,
    val bearer: String?,
    val isPending: Boolean = false,
)

/**
 * Coroutine-scoped override of the request identity, for the **add-account** flow: run the flow's
 * calls in `withContext(pendingRequestIdentity)` and every HTTP request they issue snapshots
 * `(baseUrl = the server being added, accountId = null, bearer = the staged sign-in token)` instead
 * of the live active account. Ktor's request pipeline executes in the caller's coroutine, so the
 * element reaches [SwitchGate.captureSnapshot] with no per-call plumbing — and because the *live*
 * providers are never touched, the active account's own traffic keeps flowing under its own identity
 * while another account authenticates.
 *
 * [bearer] is read per request (not captured once) because the staged token only exists after the
 * flow's sign-in call succeeds; earlier calls (config validation, the login POST itself) carry none.
 */
class PendingRequestIdentity(
    private val baseUrl: String,
    private val bearer: suspend () -> String?,
) : AbstractCoroutineContextElement(PendingRequestIdentity) {
    companion object Key : CoroutineContext.Key<PendingRequestIdentity>

    suspend fun identity(): RequestIdentity =
        RequestIdentity(baseUrl = baseUrl, accountId = null, bearer = bearer(), isPending = true)
}

/** Attribute carrying the per-request [RequestIdentity] snapshot from the barrier to the URL/auth phases. */
val RequestIdentityKey = AttributeKey<RequestIdentity>("RequestIdentity")

/**
 * The account-switch barrier. Two jobs, both closing the torn-pair race that a naive switch opens:
 *
 * 1. **Atomic snapshot.** The HTTP pipeline reads the base URL at one phase and the bearer at a later
 *    one, from two independent providers under two different locks. A switch flipping them between the
 *    two reads would send a mismatched `(urlB, bearerA)` — same-server → A's data served as B;
 *    different-server → A's bearer transmitted to B's host (credential exposure). [captureSnapshot]
 *    reads all three under [lock] in one shot so a request holds one coherent identity.
 * 2. **Park-during-flip.** [withSwitch] closes [open] and mutates the providers under the same [lock],
 *    so a new request either snapshots fully *before* the flip (and completes against the old account —
 *    whose tokens are retained, so that's correct) or parks until the flip finishes and snapshots the
 *    new account. No request ever observes a half-applied switch.
 *
 * [lock] is held only for the snapshot reads / the flip mutation — never across network I/O — so
 * per-request contention is negligible. The coordinator ([AccountSwitcher]) is the sole caller of
 * [withSwitch]; the [SwitchBarrierPlugin] (and the iOS SSE transport) are the callers of
 * [captureSnapshot].
 */
class SwitchGate(
    private val activeAccountProvider: ActiveAccountProvider,
    private val serverUrlProvider: ServerUrlProvider,
    private val tokenManager: TokenManager,
    private val accountReadyGate: AccountReadyGate?,
) {
    private val lock = Mutex()
    private val open = MutableStateFlow(true)

    /**
     * Snapshot the live identity for a request about to be built. Awaits the cold-start seed + URL
     * warm-up first (so a snapshot can never freeze `baseUrl=""` / `accountId=null` before the roster
     * reconcile has run), parks while a switch is mid-flip, then reads the triple atomically. The
     * bearer is read **keyed to the snapshot's account** (not the live cached token), so a resolved
     * account's request always carries that account's own token even while another account's sign-in
     * is staged (add-account / soft-expiry re-auth) — the triple can't tear at the token store either.
     *
     * A caller running under a [PendingRequestIdentity] (the add-account flow) short-circuits to the
     * pending identity: no gates, no live-provider reads — the pending flow is self-contained and a
     * concurrent switch doesn't affect it.
     */
    suspend fun captureSnapshot(): RequestIdentity {
        coroutineContext[PendingRequestIdentity]?.let { return it.identity() }
        accountReadyGate?.awaitReady()
        serverUrlProvider.awaitBaseUrl()
        // The gate is open >99.9% of the time (a switch is rare and user-initiated); reading the value
        // directly avoids allocating a flow collector on every request and only suspends when a switch
        // is actually mid-flip.
        if (!open.value) open.first { it }
        return lock.withLock {
            val accountId = activeAccountProvider.currentAccountId()?.value
            RequestIdentity(
                baseUrl = serverUrlProvider.getBaseUrl(),
                accountId = accountId,
                // Explicit branch (not `?: fallback`): a resolved account with an empty keyed slot
                // must yield a null bearer, never fall through to the live cache — during sign-in
                // staging the cache holds the *staged* account's token.
                bearer = if (accountId != null) {
                    tokenManager.getAccessTokenFor(accountId)
                } else {
                    tokenManager.getAccessToken()
                },
            )
        }
    }

    /**
     * Run [flip] (the URL + token-key + identity repoint) with the gate closed and under [lock], so it
     * is atomic with respect to [captureSnapshot]. New requests park until it returns; in-flight
     * requests already holding a snapshot are unaffected (they complete against the account they
     * snapshotted, whose tokens are retained). [flip] must not perform network I/O.
     */
    suspend fun <T> withSwitch(flip: suspend () -> T): T {
        open.value = false
        return try {
            lock.withLock { flip() }
        } finally {
            open.value = true
        }
    }
}
