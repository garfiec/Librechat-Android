package com.garfiec.librechat.feature.files.platform

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

@Composable
actual fun rememberFilePickerLauncher(
    onFilePick: (fileRef: Any) -> Unit,
): FilePickerLauncher {
    val launcher = rememberLauncherForActivityResult(
        contract = remember { GetContentWithMimeTypes() },
    ) { uri ->
        if (uri != null) {
            onFilePick(uri)
        }
    }
    return remember(launcher) {
        FilePickerLauncher(launchAction = { mimeTypes -> launcher.launch(mimeTypes) })
    }
}

actual class FilePickerLauncher(
    private val launchAction: (List<String>) -> Unit,
) {
    actual fun launch(mimeTypes: List<String>) {
        launchAction(mimeTypes)
    }
}

/**
 * `ACTION_GET_CONTENT` with multi-type filtering.
 *
 * `ActivityResultContracts.GetContent` takes a single MIME string, so a server allowlist like
 * "PDFs and images" can only be expressed by widening to `* / *`. Setting `EXTRA_MIME_TYPES`
 * alongside a `* / *` type is the documented way to pass several, which the system picker honors.
 */
private class GetContentWithMimeTypes : ActivityResultContract<List<String>, Uri?>() {

    override fun createIntent(context: Context, input: List<String>): Intent =
        Intent(Intent.ACTION_GET_CONTENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(input.singleOrNull() ?: ANY_TYPE)
            .apply {
                if (input.size > 1) {
                    putExtra(Intent.EXTRA_MIME_TYPES, input.toTypedArray())
                }
            }

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        intent.takeIf { resultCode == Activity.RESULT_OK }?.data

    private companion object {
        const val ANY_TYPE = "*/*"
    }
}
