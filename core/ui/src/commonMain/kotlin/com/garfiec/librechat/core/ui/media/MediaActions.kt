package com.garfiec.librechat.core.ui.media

import androidx.compose.runtime.Composable

/**
 * Returns a handler that saves the image at [url] to the device's photo gallery, or `null` on
 * platforms with no gallery-save support (iOS today only shares). The image is fetched through
 * the app's Coil singleton, so auth is handled the same way as display.
 *
 * Composable because the Android implementation wires a runtime-permission launcher (needed on
 * API < 29). Surfaces should hide their "save" affordance when this returns `null`.
 */
@Composable
expect fun rememberSaveImageToGallery(): ((url: String) -> Unit)?

/**
 * Returns a handler that shares the image at [url] via the platform share sheet. On Android the
 * image is fetched (through the Coil singleton) and shared as a file, falling back to sharing the
 * URL text on failure; on iOS the URL is shared. Composable so the Android implementation can
 * resolve a `Context` from composition (the codebase has no app-context singleton).
 */
@Composable
expect fun rememberShareImage(): (url: String) -> Unit
