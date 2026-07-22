package com.garfiec.librechat.feature.settings.platform

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext

/**
 * Android diagnostic-log saver. Uses the Storage Access Framework `CreateDocument` contract and
 * writes [content] via the resolved `OutputStream`. Mirrors the `feature:conversations` `FileSaver`
 * Android actual.
 *
 * The MIME type must stay `application/octet-stream` or SAF appends `.json` to the `.jsonl` name.
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
        contract = ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        when {
            uri == null -> onComplete(false, null)
            content == null -> onComplete(false, "Export data was lost — please try again")
            else -> try {
                val outputStream = context.contentResolver.openOutputStream(uri)
                if (outputStream == null) {
                    onComplete(false, "Could not open the selected file for writing")
                } else {
                    outputStream.use { it.write(content.toByteArray()) }
                    onComplete(true, null)
                }
            } catch (e: Exception) {
                onComplete(false, e.message ?: "Could not write export file")
            }
        }
        onReset()
    }

    LaunchedEffect(triggerFileName) {
        if (triggerFileName != null) {
            launcher.launch(triggerFileName)
        }
    }
}
