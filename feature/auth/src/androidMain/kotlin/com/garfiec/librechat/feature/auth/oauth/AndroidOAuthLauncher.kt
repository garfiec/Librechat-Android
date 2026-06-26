package com.garfiec.librechat.feature.auth.oauth

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.CookieManager
import android.widget.Toast
import androidx.browser.customtabs.CustomTabsIntent

class AndroidOAuthLauncher(
    private val context: Context,
) : OAuthLauncher {

    override fun launchOAuth(provider: String, serverUrl: String) {
        val oauthUrl = "$serverUrl/api/oauth/$provider"
        val customTabsIntent = CustomTabsIntent.Builder()
            .setShowTitle(true)
            .build()
        // Launched from the application context (not an Activity), so the Custom Tab
        // intent needs NEW_TASK or startActivity() throws AndroidRuntimeException.
        customTabsIntent.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            customTabsIntent.launchUrl(context, Uri.parse(oauthUrl))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "No browser available to sign in", Toast.LENGTH_LONG).show()
        }
    }

    override fun extractTokenFromCookies(serverUrl: String): String? {
        val cookies = CookieManager.getInstance().getCookie(serverUrl) ?: return null
        return cookies.split(";")
            .map { it.trim() }
            .firstOrNull { it.startsWith("refreshToken=") }
            ?.substringAfter("refreshToken=")
    }

    override fun clearOAuthCookie(serverUrl: String) {
        CookieManager.getInstance()
            .setCookie(serverUrl, "refreshToken=; expires=Thu, 01 Jan 1970 00:00:00 GMT")
    }
}
