package com.garfiec.librechat.feature.files.platform

import androidx.compose.runtime.Composable

/**
 * Platform file picker composable.
 * When [launch] is called, shows the platform's native file picker.
 * On file selection, calls [onFilePick] with a platform-specific file reference
 * (Android Uri, iOS NSURL).
 */
@Composable
expect fun rememberFilePickerLauncher(
    onFilePick: (fileRef: Any) -> Unit,
): FilePickerLauncher

expect class FilePickerLauncher {
    /**
     * Opens the picker filtered to [mimeTypes]. An empty list means "no restriction" and
     * shows everything — pass the result of `FileUploadConfig.pickerMimeTypes()`, which
     * already collapses unrepresentable or permissive server allowlists to empty.
     */
    fun launch(mimeTypes: List<String>)
}
