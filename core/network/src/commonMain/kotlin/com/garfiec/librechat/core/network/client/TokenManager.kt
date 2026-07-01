package com.garfiec.librechat.core.network.client

import kotlinx.coroutines.flow.SharedFlow

interface TokenManager {
    /** Non-suspend check — returns true when an access token is cached in memory. */
    val isAuthenticated: Boolean
    suspend fun getAccessToken(): String?
    suspend fun setTokens(accessToken: String, refreshToken: String)
    suspend fun refreshAccessToken(): Boolean
    suspend fun clearTokens()

    /**
     * Bind token storage to [accountId] once identity resolves (login, cold-start restore, upgrade).
     * Re-homes tokens written under the previous key (the bare keys on a legacy/first install, or the
     * prior account) into this account's keyed slot and repoints the synchronously-readable mirror, so
     * subsequent cold starts seed the right account's bearer with no blind window. Idempotent when the
     * account is unchanged.
     */
    suspend fun onAccountResolved(accountId: String)

    /**
     * Clear the active account's tokens and the mirror on logout. Distinct from [clearTokens] (the
     * refresh-failure path) in that it also drops the persisted active-account pointer.
     */
    suspend fun onAccountCleared()

    fun emitSessionExpired()
    val sessionExpiredFlow: SharedFlow<Unit>
}
