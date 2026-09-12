package com.garfiec.librechat.feature.auth.screen

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.garfiec.librechat.feature.auth.oauth.extractQueryParameter
import com.garfiec.librechat.feature.auth.oauth.extractRefreshTokenFromCookies

// Chrome UA without the `; wv` marker: the server's ua-parser middleware 403s WebView agents,
// and Google blocks WebView sign-ins.
private const val BROWSER_UA =
    "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Mobile Safari/537.36"

@SuppressLint("SetJavaScriptEnabled")
@Composable
actual fun SsoWebView(
    serverUrl: String,
    provider: String,
    onTokenCaptured: (String) -> Unit,
    onOAuthError: (String?) -> Unit,
    modifier: Modifier,
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                settings.userAgentString = BROWSER_UA
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?,
                    ): Boolean = false

                    override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                        super.doUpdateVisitedHistory(view, url, isReload)
                        url?.let { onPageEvent(serverUrl, it, onTokenCaptured, onOAuthError) }
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        url?.let { onPageEvent(serverUrl, it, onTokenCaptured, onOAuthError) }
                    }
                }
                loadUrl("$serverUrl/oauth/$provider")
            }
        },
        onRelease = { webView ->
            webView.stopLoading()
            webView.destroy()
        },
    )
}

// Only cookies the server itself set are visible for its URL — capture is origin-scoped by
// construction, so no further URL-host validation is needed.
private fun onPageEvent(
    serverUrl: String,
    url: String,
    onTokenCaptured: (String) -> Unit,
    onOAuthError: (String?) -> Unit,
) {
    extractQueryParameter(url, "error")?.let(onOAuthError)
    val cookieHeader = CookieManager.getInstance().getCookie(serverUrl)
    extractRefreshTokenFromCookies(cookieHeader)?.let(onTokenCaptured)
}