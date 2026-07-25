package com.garfiec.librechat.feature.files.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

@Composable
actual fun rememberFilePickerLauncher(
    onFilePick: (fileRef: Any) -> Unit,
): FilePickerLauncher {
    // iOS file picker — stub for now.
    // A proper implementation would use UIDocumentPickerViewController.
    return remember { FilePickerLauncher() }
}

actual class FilePickerLauncher {
    actual fun launch(mimeTypes: List<String>) {
        // No-op on iOS for now
    }
}
