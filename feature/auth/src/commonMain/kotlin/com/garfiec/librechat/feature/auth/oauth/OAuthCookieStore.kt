package com.garfiec.librechat.feature.auth.oauth

/** Platform cookie-jar access for the SSO WebView flow: wipes the server's stale `refreshToken`
 *  cookie before an SSO round-trip so only a cookie minted by that round-trip can be captured. */
interface OAuthCookieStore {
    fun clearRefreshTokenCookie(serverUrl: String)
}