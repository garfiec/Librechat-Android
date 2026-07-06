package com.garfiec.librechat.feature.chat.components.artifact

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import com.garfiec.librechat.feature.chat.components.shareArtifact
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.cValue
import platform.Foundation.NSURL
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration

/**
 * iOS preview surface — a `WKWebView` hosting the artifact's rendered HTML. The
 * shell, header, selector, and code body are shared in the common `ArtifactPanel`.
 */
@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun ArtifactPreviewSurface(
    content: String,
    type: String,
    isDarkTheme: Boolean,
    modifier: Modifier,
) {
    val html = remember(content, type, isDarkTheme) {
        ArtifactWebContent.buildHtml(content, type, isDarkTheme, inline = false)
    }

    // Track what HTML is currently loaded to avoid reloading on every recomposition —
    // version-nav swipes and shell animations otherwise re-trigger loadHTMLString and
    // flash the WebView.
    var loadedHtml by remember { mutableStateOf("") }
    UIKitView(
        modifier = modifier.fillMaxSize(),
        factory = {
            val config = WKWebViewConfiguration().apply {
                defaultWebpagePreferences.allowsContentJavaScript = true
            }
            val webView = WKWebView(
                frame = cValue { },
                configuration = config,
            )
            webView.setOpaque(false)
            webView.loadHTMLString(
                html,
                baseURL = NSURL.URLWithString("https://cdn.jsdelivr.net"),
            )
            loadedHtml = html
            webView
        },
        update = { webView ->
            if (html != loadedHtml) {
                webView.loadHTMLString(
                    html,
                    baseURL = NSURL.URLWithString("https://cdn.jsdelivr.net"),
                )
                loadedHtml = html
            }
        },
    )
}

@Composable
actual fun rememberShareArtifact(): (Artifact) -> Unit = remember {
    { artifact ->
        shareArtifact(
            title = artifact.title,
            content = artifact.content,
            language = artifact.language ?: "",
        )
    }
}

// iOS has no API to place a home-screen launcher icon, so the affordance is unavailable.
@Composable
actual fun rememberAddArtifactToHomeScreen(): ((artifact: Artifact, label: String, emoji: String?) -> Unit)? = null
