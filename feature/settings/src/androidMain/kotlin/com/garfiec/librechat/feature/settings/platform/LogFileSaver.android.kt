package com.garfiec.librechat.feature.settings.platform

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext

/**
 * Android diagnostic-log saver. Uses the Storage Access Framework `CreateDocument` contract
 * (`application/json` MIME) and writes [content] via the resolved `OutputStream`. Mirrors the
 * `feature:conversations` `FileSaver` Android actual.
 */
@Composable
actual fun LogFileSaver(
    triggerFileName: String?,
    content: String?,
    onComplete: (success: Boolean, message: String?) -> Unit,
    onReset: () -> Unit,
) {
    val context = LocalContext.current

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null && content != null) {
            try {
                context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                    outputStream.write(content.toByteArray())
                }
                onComplete(true, null)
            } catch (e: Exception) {
                onComplete(false, e.message)
            }
        } else {
            // User cancelled the picker.
            onComplete(false, null)
        }
        onReset()
    }

    LaunchedEffect(triggerFileName) {
        if (triggerFileName != null) {
            launcher.launch(triggerFileName)
        }
    }
}
