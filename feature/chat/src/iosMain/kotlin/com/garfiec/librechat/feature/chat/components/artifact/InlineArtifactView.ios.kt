package com.garfiec.librechat.feature.chat.components.artifact

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.UIKitView
import com.garfiec.librechat.core.ui.theme.isSurfaceDark
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.cValue
import kotlinx.cinterop.useContents
import platform.Foundation.NSURL
import platform.UIKit.UIScreen
import com.garfiec.librechat.feature.chat.components.web.loadVendoredHtml
import com.garfiec.librechat.feature.chat.components.web.rememberWebAssetBaseUrl
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration

/**
 * iOS inline artifact preview. The slot is hardcoded to the inline cap for
 * every type routed here (HTML, React, uncached Mermaid) — matching Android.
 * SVG and cached Mermaid take the synchronous-aspect-ratio path via
 * `InlineSvgSurface` upstream; Markdown renders natively. iOS does not
 * participate in the mermaid SVG cache (Coil 3 on Apple uses Skia SVGDOM, not
 * AndroidSVG — fidelity is unverified). Full content lives in the fullscreen
 * [ArtifactPanel] on tap.
 */
@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun InlineArtifactView(
    artifact: Artifact,
    onTap: () -> Unit,
    modifier: Modifier,
) {
    val isDarkTheme = isSurfaceDark()
    val html = remember(artifact, isDarkTheme) {
        ArtifactWebContent.buildHtml(artifact.content, artifact.type, isDarkTheme, inline = true)
    }

    // Match the Android cap (see InlineArtifactSizing.kt). On iOS, points map
    // 1:1 to Compose Dp, so UIScreen.mainScreen.bounds.size.height is already
    // in the unit we want.
    val maxInlineHeightDp = remember {
        UIScreen.mainScreen.bounds.useContents { size.height * INLINE_MAX_HEIGHT_FRACTION }.dp
    }

    val assetBase = rememberWebAssetBaseUrl() ?: return

    val uiKitViewBlock: @Composable () -> Unit = {
        var loadedHtml by remember { mutableStateOf("") }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(2.dp),
        ) {
            UIKitView(
                modifier = Modifier.fillMaxWidth().height(maxInlineHeightDp),
                factory = {
                    val config = WKWebViewConfiguration().apply {
                        defaultWebpagePreferences.allowsContentJavaScript = true
                    }
                    val webView = WKWebView(frame = cValue { }, configuration = config)
                    webView.setOpaque(false)
                    webView.scrollView.setScrollEnabled(false)
                    webView.loadVendoredHtml(html, assetBase)
                    loadedHtml = html
                    webView
                },
                update = { webView ->
                    if (html != loadedHtml) {
                        webView.loadVendoredHtml(html, assetBase)
                        loadedHtml = html
                    }
                },
                onRelease = { webView ->
                    webView.stopLoading()
                },
            )
        }
    }

    ArtifactCardSurface(onTap = onTap, modifier = modifier) {
        uiKitViewBlock()
    }
}
