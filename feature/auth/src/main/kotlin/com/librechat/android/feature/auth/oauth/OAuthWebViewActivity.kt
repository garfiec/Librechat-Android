package com.librechat.android.feature.auth.oauth

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback

/**
 * Runs the LibreChat OAuth flow inside a [WebView] so [CookieManager] can read the
 * httpOnly `refreshToken` cookie. Chrome Custom Tabs use Chrome's cookie jar, which
 * does not sync with the app's [CookieManager], so users previously stayed in the browser.
 */
class OAuthWebViewActivity : ComponentActivity() {

    private var webView: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val serverUrl = intent.getStringExtra(EXTRA_SERVER_URL)?.trimEnd('/') ?: run {
            finish()
            return
        }
        val provider = intent.getStringExtra(EXTRA_PROVIDER) ?: run {
            finish()
            return
        }
        val serverHost = Uri.parse(serverUrl).host?.lowercase() ?: run {
            finish()
            return
        }

        val wv = WebView(this)
        webView = wv
        CookieManager.getInstance().setAcceptCookie(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
        }

        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // Avoid GitHub / IdPs treating the session as an embedded "WebView" UA when possible
            userAgentString = userAgentString.replace("; wv)", ")")
        }

        val tryFinish: () -> Boolean = {
            CookieManager.getInstance().flush()
            val token = lookupRefreshToken(serverUrl)
            if (token != null) {
                setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_REFRESH_TOKEN, token))
                finish()
                true
            } else {
                false
            }
        }

        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                return false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (url.isNullOrBlank()) return
                if (!isOurServer(url, serverHost)) return
                // Cookie may be set slightly after DOM load
                view?.post {
                    tryFinish()
                }
            }
        }

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (wv.canGoBack()) {
                        wv.goBack()
                    } else {
                        isEnabled = false
                        setResult(Activity.RESULT_CANCELED)
                        finish()
                    }
                }
            },
        )

        val startUrl = "$serverUrl/oauth/$provider"
        wv.loadUrl(startUrl)
        setContentView(wv)
    }

    override fun onDestroy() {
        webView?.apply {
            stopLoading()
            (parent as? android.view.ViewGroup)?.removeView(this)
            destroy()
        }
        webView = null
        super.onDestroy()
    }

    private fun isOurServer(pageUrl: String, serverHost: String): Boolean {
        return try {
            val host = Uri.parse(pageUrl).host?.lowercase()
            host == serverHost
        } catch (_: Exception) {
            false
        }
    }

    private fun lookupRefreshToken(serverUrl: String): String? {
        val uri = Uri.parse(serverUrl)
        val scheme = uri.scheme ?: "https"
        val host = uri.host ?: return parseRefreshToken(CookieManager.getInstance().getCookie(serverUrl))
        val origin = "$scheme://$host"
        val cm = CookieManager.getInstance()
        return listOf(serverUrl, origin, "$origin/")
            .mapNotNull { cm.getCookie(it) }
            .firstNotNullOfOrNull { parseRefreshToken(it) }
    }

    companion object {
        const val EXTRA_SERVER_URL: String = "extra_server_url"
        const val EXTRA_PROVIDER: String = "extra_provider"
        const val EXTRA_REFRESH_TOKEN: String = "extra_refresh_token"

        fun createIntent(context: Context, serverUrl: String, provider: String): Intent =
            Intent(context, OAuthWebViewActivity::class.java).apply {
                putExtra(EXTRA_SERVER_URL, serverUrl.trimEnd('/'))
                putExtra(EXTRA_PROVIDER, provider.lowercase())
            }
    }
}

internal fun parseRefreshToken(cookieHeader: String?): String? {
    if (cookieHeader.isNullOrBlank()) return null
    return cookieHeader.split(";")
        .map { it.trim() }
        .firstOrNull { it.startsWith("refreshToken=", ignoreCase = true) }
        ?.substringAfter("refreshToken=")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
}
