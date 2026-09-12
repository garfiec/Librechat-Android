package com.garfiec.librechat.feature.auth.oauth

import platform.Foundation.NSHTTPCookie
import platform.Foundation.NSHTTPCookieStorage
import platform.Foundation.NSURL

class IosOAuthCookieStore : OAuthCookieStore {
    override fun clearRefreshTokenCookie(serverUrl: String) {
        val url = NSURL.URLWithString(serverUrl) ?: return
        val cookies = NSHTTPCookieStorage.sharedHTTPCookieStorage.cookiesForURL(url) ?: return

        @Suppress("UNCHECKED_CAST")
        val cookieList = cookies as List<NSHTTPCookie>
        cookieList.filter { it.name == REFRESH_TOKEN_COOKIE }.forEach {
            NSHTTPCookieStorage.sharedHTTPCookieStorage.deleteCookie(it)
        }
    }
}