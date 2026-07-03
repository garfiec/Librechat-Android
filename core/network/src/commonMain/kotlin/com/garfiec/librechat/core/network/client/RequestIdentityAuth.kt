package com.garfiec.librechat.core.network.client

/**
 * The one 401-recovery path shared by every transport (Ktor [AuthInterceptorPlugin] and the iOS raw
 * SSE transport): refresh the account the request was snapshotted for — keyed and URL-pinned to the
 * snapshot, so a switch that raced the request refreshes the right account against the right server,
 * never the live one — and return the refreshed bearer. Without a snapshot (refresh client / tests)
 * falls back to the live active account, preserving legacy behavior.
 *
 * Returns null when the refresh failed or the refreshed slot came back empty (a logout/removal raced
 * the refresh); callers treat null as session-expired for `identity?.accountId`.
 */
suspend fun TokenManager.refreshBearerFor(identity: RequestIdentity?): String? {
    val account = identity?.accountId
    val refreshed = if (account != null) {
        refreshAccessTokenFor(account, identity.baseUrl)
    } else {
        refreshAccessToken()
    }
    if (!refreshed) return null
    return if (account != null) getAccessTokenFor(account) else getAccessToken()
}
