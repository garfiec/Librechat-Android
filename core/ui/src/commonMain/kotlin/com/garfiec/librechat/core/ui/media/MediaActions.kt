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

/**
 * Returns a handler that shares an arbitrary file (the already-downloaded [bytes]) via the platform
 * share sheet, letting the user open it in another app, save to Files, AirDrop, etc. Unlike
 * [rememberShareImage] this takes raw bytes because non-image files aren't in Coil's cache — the
 * caller downloads them (authenticated) first. [filename] names the temp file (so the chooser shows
 * the real name + extension) and [mime] hints the content type when known.
 *
 * Composable so the Android implementation can resolve a `Context` from composition.
 */
@Composable
expect fun rememberShareFile(): (bytes: ByteArray, filename: String, mime: String?) -> Unit
