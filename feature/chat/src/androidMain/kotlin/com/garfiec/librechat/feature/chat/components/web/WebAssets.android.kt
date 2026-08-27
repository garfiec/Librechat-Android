package com.garfiec.librechat.feature.chat.components.web

import com.garfiec.librechat.feature.chat.resources.Res
import org.jetbrains.compose.resources.ExperimentalResourceApi

/**
 * Compose Resources land in the APK's assets, so the WebView reads them in place —
 * no copy, no file-system permission. `file:///android_asset` stays readable with
 * `allowFileAccess = false`, which is why the renderers can keep that setting off
 * while still loading bundled scripts.
 */
@OptIn(ExperimentalResourceApi::class)
internal actual suspend fun webAssetBaseUrl(): String {
    // Derived rather than hardcoded: the assets layout is a Compose Resources
    // implementation detail that has moved between versions.
    val anchorUri = Res.getUri("${VendoredWebAssets.ROOT}/${VendoredWebAssets.ANCHOR}")
    return anchorUri.removeSuffix(VendoredWebAssets.ANCHOR)
}
