package com.garfiec.librechat.feature.settings.platform

import androidx.compose.runtime.Composable

/**
 * Composable that provides a file-save / share callback for diagnostic-log export.
 *
 * When [triggerFileName] changes to a non-null value, the platform prompts the user to save
 * (Android: Storage Access Framework) or share (iOS: `UIActivityViewController`) a file with
 * that name containing [content]. [onComplete] reports success/failure; [onReset] clears the
 * trigger state so a recomposition won't re-launch the picker.
 *
 * `feature:settings` owns this rather than reusing `feature:conversations`' `FileSaver`, because
 * feature modules may depend on `:core:*` only — never on each other.
 */
@Composable
expect fun LogFileSaver(
    triggerFileName: String?,
    content: String?,
    onComplete: (success: Boolean, message: String?) -> Unit,
    onReset: () -> Unit,
)
