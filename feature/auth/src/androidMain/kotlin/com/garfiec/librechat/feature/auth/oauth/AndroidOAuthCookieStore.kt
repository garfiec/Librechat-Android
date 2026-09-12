package com.garfiec.librechat.feature.auth.oauth

import android.webkit.CookieManager

class AndroidOAuthCookieStore : OAuthCookieStore {
    override fun clearRefreshTokenCookie(serverUrl: String) {
        CookieManager.getInstance()
            .setCookie(serverUrl, "$REFRESH_TOKEN_COOKIE=; expires=Thu, 01 Jan 1970 00:00:00 GMT")
    }
}