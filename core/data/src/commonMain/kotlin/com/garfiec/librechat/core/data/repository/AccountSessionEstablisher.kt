package com.garfiec.librechat.core.data.repository

import co.touchlab.kermit.Logger
import com.garfiec.librechat.core.common.extensions.serverHostLabel
import com.garfiec.librechat.core.common.identity.AccountId
import com.garfiec.librechat.core.common.identity.deriveAccountId
import com.garfiec.librechat.core.common.identity.deriveServerId
import com.garfiec.librechat.core.data.datastore.AccountEntry
import com.garfiec.librechat.core.data.datastore.AccountRegistry
import com.garfiec.librechat.core.model.User
import com.garfiec.librechat.core.network.client.ServerUrlProvider
import com.garfiec.librechat.core.network.client.TokenManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock

/**
 * Turns a freshly-authenticated (or cold-start-restored) [User] into the active [AccountId] and
 * publishes it. The single place
 * the three login paths and the cold-start restore funnel through so identity is derived identically
 * everywhere.
 *
 * Derivation is `deriveAccountId(deriveServerId(baseUrl), userKey)` where **`userKey` is the user's
 * Mongo `_id`** — the same ObjectId that historically populates the `user` column on cached tenant
 * rows (`ConversationMapper`, `TagMapper`). It is taken from [User.id] (the API serializes `_id` → `id`
 * on the user payload), falling back to [User.mongoId], and is **never** the email: the legacy claim
 * stamps `WHERE user = :userKey` and then deletes everything still unclaimed, so a key that doesn't
 * match the `user` column would wipe the upgrading user's whole cache.
 */
class AccountSessionEstablisher(
    private val accountRegistry: AccountRegistry,
    private val claimReconciler: AccountClaimReconciler,
    private val serverUrlProvider: ServerUrlProvider,
    private val tokenManager: TokenManager,
    private val ioDispatcher: CoroutineDispatcher,
) {

    /**
     * Resolves the [AccountId] for [user], runs the one-time legacy claim, then persists + publishes
     * it. Idempotent: re-establishing the already-active account re-publishes it and the marker-guarded
     * claim short-circuits. Returns the resolved id.
     *
     * **Claim before publish** is load-bearing: publishing flips `ActiveAccountProvider` to
     * `Resolved(accountId)`, after which account-scoped reads/writes go live. Legacy pre-migration rows
     * still carry `accountId IS NULL` until the claim stamps them, so a sync running in the window
     * between publish and claim would read them as foreign (`getByIdForAccount` misses a NULL row) and
     * an `upsertPreservingTags` would overwrite the row, dropping locally-stored tags. Claiming first
     * keeps the provider at `Resolved(null)` (writes skip when unresolved) until every legacy row is
     * attributed, closing that window.
     *
     * The claim is nonetheless **best-effort**: a *failure* must never abort establishment. It is
     * idempotent and only writes its done-marker on success, so a failure (e.g. a transient DB error)
     * retries on the next establish. Letting it propagate would skip [AccountRegistry.upsertActive]
     * and strand an authenticated user at `Resolved(null)` — every scoped read/write silently skipped
     * for the session — so a claim failure is caught here and the account is published regardless.
     * **Cancellation is the exception**: a `CancellationException` is rethrown, so a session torn down
     * mid-claim aborts cleanly instead of publishing an account for a coroutine that is being cancelled.
     *
     * The cost: the still-unclaimed legacy rows stay at `accountId IS NULL`, invisible to every
     * account-filtered read, and the overwrite window above stays open. Recovery is coupled to the next
     * establish (cold-start restore or a re-login). A session that logs in once and stays logged in
     * never re-establishes, so on that path the orphaned pre-migration history is hidden until the user
     * re-auths — not just for a brief window. That is still strictly better than a dead session;
     * bounding it with a foreground/sync-time claim retry is left to a follow-up (SessionWriter / PR1-B).
     */
    suspend fun establish(user: User): AccountId = withContext(ioDispatcher) {
        val baseUrl = serverUrlProvider.awaitBaseUrl()
        val userKey = user.accountUserKey()
        val serverId = deriveServerId(baseUrl)
        val accountId = deriveAccountId(serverId, userKey)
        runCatching { claimReconciler.claimIfNeeded(accountId, userKey) }
            .onFailure { error ->
                // A cancelled establish (logout / teardown during cold-start restore) must abort, not
                // publish the account for a session being torn down — rethrow rather than swallow it.
                if (error is CancellationException) throw error
                Logger.w(error) { "Legacy account claim failed; publishing account anyway (will retry)" }
            }
        // Bind token storage to this account (re-homes bare/legacy or prior-account tokens into the
        // keyed slot + repoints the sync mirror) before publishing identity, so a cold-start restore
        // resolving the same account is a no-op and the next launch seeds the right bearer.
        tokenManager.onAccountResolved(accountId.value)
        // Record the roster entry (with the display label/avatar from the live user) and publish it.
        accountRegistry.upsertActive(
            AccountEntry(
                accountId = accountId.value,
                serverUrl = baseUrl,
                displayLabel = user.displayLabel(baseUrl),
                avatarUrl = user.avatar?.takeIf { it.isNotBlank() },
                lastActiveAt = Clock.System.now().toEpochMilliseconds(),
            ),
        )
        accountId
    }
}

/**
 * The stable per-user key an [AccountId] is derived from — the user's Mongo `_id` (see the class
 * KDoc above for why it must never be the email). Shared by the login/cold-start establish and the
 * add-account completion so both derive identity identically.
 */
internal fun User.accountUserKey(): String {
    val userKey = id ?: mongoId
    require(!userKey.isNullOrBlank()) {
        "Authenticated user has no id; cannot derive an account owner"
    }
    return userKey
}

/** Best display name for the switcher chip, falling back to the server host when the user is unnamed. */
internal fun User.displayLabel(baseUrl: String): String =
    name?.takeIf { it.isNotBlank() }
        ?: username?.takeIf { it.isNotBlank() }
        ?: email.takeIf { it.isNotBlank() }
        ?: baseUrl.serverHostLabel()
