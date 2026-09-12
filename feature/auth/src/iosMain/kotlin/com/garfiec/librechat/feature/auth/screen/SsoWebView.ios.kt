package com.garfiec.librechat.feature.auth.screen

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import com.garfiec.librechat.feature.auth.oauth.REFRESH_TOKEN_COOKIE
import com.garfiec.librechat.feature.auth.oauth.extractQueryParameter
import com.garfiec.librechat.feature.auth.oauth.extractRefreshTokenFromCookies
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.cValue
import platform.Foundation.HTTPCookie
import platform.Foundation.HTTPCookieStorage
import platform.Foundation.NSURL
import platform.Foundation.NSURLURLRequest
import platform.WebKit.WKNavigation
import platform.WebKit.WKNavigationDelegateProtocol
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.darwin.NSObject
import platform.Foundation.NSHTTPCookie

// Safari UA: big providers block embedded-browser sign-ins; WKWebView's default agent trips that.
private const val BROWSER_UA =
    "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1"

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun SsoWebView(
    serverUrl: String,
    provider: String,
    onTokenCaptured: (String) -> Unit,
    onOAuthError: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    UIKitView(
        modifier = modifier,
        factory = {
            val config = WKWebViewConfiguration().apply {
                defaultWebpagePreferences.allowsContentJavaScript = true
            }
            val webView = WKWebView(frame = cValue { }, configuration = config)
            webView.customUserAgent = BROWSER_UA
            webView.navigationDelegate = object : NSObject(), WKNavigationDelegateProtocol {
                override fun webView(webView: WKWebView, didFinishNavigation: WKNavigation?) {
                    webView.URL?.absoluteString?.let { url ->
                        onOAuthError(extractQueryParameter(url, "error"))
                        captureToken(serverUrl, onTokenCaptured)
                    }
                }

                override fun webView(
                    webView: WKWebView,
                    didReceiveServerRedirectForProvisionalNavigation: WKNavigation?,
                ) {
                    // Redirects happen before didFinish; the callback response that sets the
                    // cookie may be a redirect chain, so also try here.
                    webView.URL?.absoluteString?.let { url ->
                        onOAuthError(extractQueryParameter(url, "error"))
                        captureToken(serverUrl, onTokenCaptured)
                    }
                }
            }
            webView.loadRequest(NSURLURLRequest(NSURL.URLWithString("$serverUrl/oauth/$provider")))
            webView
        },
        update = { webView ->
            webView.customUserAgent = BROWSER_UA
        },
        onRelease = { webView ->
            webView.stopLoading()
        },
    )
}

// WKWebView cookies live in the WKHTTPCookieStore (not NSHTTPCookieStorage); read them from the
// shared default store. Only cookies the server itself set match its host — origin-scoped by
// construction.
private fun captureToken(serverUrl: String, onTokenCaptured: (String) -> Unit) {
    val serverHost = NSURL.URLWithString(serverUrl)?.host ?: return
    val cookieStore = platform.WebKit.WKWebsiteDataStore.defaultDataStore().httpCookieStore
    cookieStore.getAllCookies { cookies ->
        @Suppress("UNCHECKED_CAST")
        val cookieList = cookies as Map<String, HTTPCookie>
        val header = cookieList.values
            .filter { it.domain.removePrefix(".").endsWith(serverHost) }
            .joinToString("; ") { "${it.name}=${it.value}" }
        extractRefreshTokenFromCookies(header)?.let(onTokenCaptured)
    }
}