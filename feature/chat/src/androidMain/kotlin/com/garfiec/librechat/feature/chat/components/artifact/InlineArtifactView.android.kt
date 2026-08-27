package com.garfiec.librechat.feature.chat.components.artifact

import android.annotation.SuppressLint
import android.net.http.SslError
import android.view.ViewGroup
import android.webkit.SslErrorHandler
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.garfiec.librechat.feature.chat.components.web.rememberWebAssetBaseUrl
import co.touchlab.kermit.Logger
import com.garfiec.librechat.core.ui.theme.isSurfaceDark
import com.garfiec.librechat.feature.chat.components.web.configureLazyListWebView
import com.garfiec.librechat.feature.chat.components.web.safelyDestroyWebView

/**
 * Android inline artifact preview. The slot is hardcoded to the inline cap for
 * every type routed here (HTML, React, uncached Mermaid). SVG and cached
 * Mermaid take the synchronous-aspect-ratio path via `InlineSvgSurface`
 * upstream; Markdown renders natively. Full content lives in the fullscreen
 * [ArtifactPanel] on tap. A fixed slot is the only configuration we've
 * measured that fully eliminates LazyColumn scroll-jump for WebView-hosted
 * previews.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
actual fun InlineArtifactView(
    artifact: Artifact,
    onTap: () -> Unit,
    modifier: Modifier,
) {
    val isDarkTheme = isSurfaceDark()
    val bgArgb = MaterialTheme.colorScheme.surface.toArgb()
    val html = remember(artifact, isDarkTheme) {
        ArtifactWebContent.buildHtml(artifact.content, artifact.type, isDarkTheme, inline = true)
    }
    var loadedHtml by remember { mutableStateOf("") }

    val assetBase = rememberWebAssetBaseUrl() ?: return

    val cache = LocalMermaidRenderCache.current

    val mermaidKey: String? = remember(artifact.content, artifact.type, isDarkTheme) {
        if (ArtifactType.from(artifact.type) == ArtifactType.MERMAID &&
            isCacheableMermaid(artifact.content)
        ) {
            mermaidCacheKey(artifact.content, isDarkTheme)
        } else {
            null
        }
    }

    // Inline previews are capped to ~70% of the current window so a long HTML /
    // React doc can never claim multiple screens of LazyColumn space (the full
    // content lives in the fullscreen ArtifactPanel on tap). A second reason
    // the cap matters on Android: `LAYER_TYPE_SOFTWARE` on the inline WebView
    // (see `WebViewSafeDestroy.kt`) allocates a backing bitmap sized to the
    // WebView's full layout height — an unbounded 380×4500 dp layout produces
    // either a paint-skipping software-bitmap-too-large failure or an OOM that
    // shows up as a blank card. Capping the layout keeps the bitmap small.
    val maxInlineHeightDp = (LocalConfiguration.current.screenHeightDp * INLINE_MAX_HEIGHT_FRACTION).dp

    val androidViewBlock: @Composable () -> Unit = {
        AndroidView(
            modifier = Modifier
                .padding(2.dp)
                .fillMaxWidth()
                .height(maxInlineHeightDp),
            factory = { context ->
                WebView(context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    configureLazyListWebView(this)
                    setBackgroundColor(bgArgb)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    isVerticalScrollBarEnabled = false
                    isHorizontalScrollBarEnabled = false
                    webViewClient = object : WebViewClient() {
                        override fun onReceivedSslError(
                            view: WebView?,
                            handler: SslErrorHandler?,
                            error: SslError?,
                        ) {
                            handler?.cancel()
                            Logger.w { "SSL error in inline artifact WebView: ${error?.primaryError}" }
                        }
                    }
                    // Must run before loadDataWithBaseURL so MermaidBridge is
                    // bound when mermaid's IIFE executes.
                    if (mermaidKey != null) {
                        val receiver = MermaidBridgeReceiver(cache, mermaidKey)
                        addJavascriptInterface(MermaidJsBridge(receiver), "MermaidBridge")
                    }
                    loadDataWithBaseURL(assetBase, html, "text/html", "UTF-8", null)
                    loadedHtml = html
                }
            },
            update = { webView ->
                webView.setBackgroundColor(bgArgb)
                if (html != loadedHtml) {
                    webView.loadDataWithBaseURL(assetBase, html, "text/html", "UTF-8", null)
                    loadedHtml = html
                }
            },
            onRelease = ::safelyDestroyWebView,
        )
    }

    ArtifactCardSurface(onTap = onTap, modifier = modifier) {
        if (mermaidKey != null) key(mermaidKey) { androidViewBlock() } else androidViewBlock()
    }
}
