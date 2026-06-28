package com.garfiec.librechat.core.data.repository

import com.garfiec.librechat.core.common.identity.AccountId
import com.garfiec.librechat.core.common.identity.deriveAccountId
import com.garfiec.librechat.core.common.identity.deriveServerId
import com.garfiec.librechat.core.data.datastore.AccountRegistry
import com.garfiec.librechat.core.model.User
import com.garfiec.librechat.core.network.client.ServerUrlProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

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
    private val ioDispatcher: CoroutineDispatcher,
) {

    /**
     * Resolves the [AccountId] for [user], persists + publishes it, then runs the one-time legacy
     * claim. Idempotent: re-establishing the already-active account just re-publishes it and the
     * marker-guarded claim short-circuits. Returns the resolved id.
     */
    suspend fun establish(user: User): AccountId = withContext(ioDispatcher) {
        val baseUrl = serverUrlProvider.awaitBaseUrl()
        val userKey = user.id ?: user.mongoId
        require(!userKey.isNullOrBlank()) {
            "Authenticated user has no id; cannot derive an account owner"
        }
        val accountId = deriveAccountId(deriveServerId(baseUrl), userKey)
        accountRegistry.setActiveAccount(accountId)
        claimReconciler.claimIfNeeded(accountId, userKey)
        accountId
    }
}
