package com.garfiec.librechat.feature.skills.components

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import co.touchlab.kermit.Logger

@Composable
actual fun rememberSkillFilePicker(
    onPick: (PickedDocument) -> Unit,
): SkillFilePicker {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            context.readPickedDocument(uri)?.let(onPick)
        }
    }
    return remember(launcher) {
        SkillFilePicker(launchAction = { mimeTypes ->
            launcher.launch(mimeTypes.ifEmpty { listOf("*/*") }.toTypedArray())
        })
    }
}

actual class SkillFilePicker(
    private val launchAction: (List<String>) -> Unit,
) {
    actual fun launch(mimeTypes: List<String>) {
        launchAction(mimeTypes)
    }
}

private fun Context.readPickedDocument(uri: Uri): PickedDocument? {
    return try {
        val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        var name = uri.lastPathSegment ?: "file"
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) cursor.getString(idx)?.let { name = it }
            }
        }
        // Markdown/zip often resolve to octet-stream via the resolver; infer from
        // the extension so the multipart Content-Type is meaningful.
        val mime = skillFileMimeFromExtension(name.substringAfterLast('.', "").lowercase())
            ?: contentResolver.getType(uri)
            ?: "application/octet-stream"
        PickedDocument(bytes = bytes, filename = name, mimeType = mime)
    } catch (e: Exception) {
        Logger.w(e) { "SkillFilePicker: failed to read picked document" }
        null
    }
}
