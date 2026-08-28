package com.garfiec.librechat.feature.chat.components.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState

/**
 * Locates the vendored web assets ([VendoredWebAssets]) and returns the base URL the
 * WebView renderers load their HTML against.
 *
 * Every renderer's HTML references its scripts relatively (`katex/katex.min.js`), so the
 * base URL is the only platform-specific part: resolve it, hand it to the WebView as the
 * document base, and the same HTML works on both platforms. Nothing is fetched over the
 * network, which is why the CSPs in those documents name no remote origin at all.
 *
 * The two platforms cannot share an implementation. Android reads the assets in place.
 * iOS has to copy them out of the framework bundle first, because WKWebView refuses to
 * load local subresources for a page passed to `loadHTMLString` — the page and the files
 * it references must sit together under one directory granted via `loadFileURL`.
 *
 * @return an absolute directory URL ending in `/`.
 */
internal expect suspend fun webAssetBaseUrl(): String

/**
 * [webAssetBaseUrl] for composables, `null` until it resolves.
 *
 * Android resolves on the first frame; iOS may take longer the first time, while it
 * copies. Callers should render nothing until this is non-null rather than loading the
 * WebView with a placeholder base — a document loaded against the wrong base silently
 * renders unstyled instead of failing.
 */
@Composable
internal fun rememberWebAssetBaseUrl(): String? =
    produceState<String?>(initialValue = null) { value = webAssetBaseUrl() }.value
