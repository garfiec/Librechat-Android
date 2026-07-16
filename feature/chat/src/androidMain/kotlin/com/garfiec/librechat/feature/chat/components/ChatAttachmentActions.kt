package com.garfiec.librechat.feature.chat.components

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File

/**
 * The three attachment entry points offered by the chat tools sheet, ready to invoke.
 * Produced by [rememberChatAttachmentActions].
 */
@Immutable
class ChatAttachmentActions(
    val onAttachFiles: () -> Unit,
    val onTakePhoto: () -> Unit,
    val onPickPhotos: () -> Unit,
)

/**
 * Registers the file-picker, photo-picker, camera, and camera-permission activity-result launchers
 * and returns the [ChatAttachmentActions] that drive them. Call this **once** high in the chat
 * screen and share the result: a launcher stays usable from any descendant composition while the
 * one that registered it is alive, so the chat screen registers a single set and passes it down to
 * both the composer's "+" sheet ([ChatInput]) and the pull-up sheet — no need for a second copy.
 */
@Composable
fun rememberChatAttachmentActions(
    onFilesSelected: (List<Uri>) -> Unit,
): ChatAttachmentActions {
    val context = LocalContext.current

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNotEmpty()) {
            onFilesSelected(uris)
        }
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(),
    ) { uris ->
        if (uris.isNotEmpty()) {
            onFilesSelected(uris)
        }
    }

    var cameraPhotoUri by remember { mutableStateOf<Uri?>(null) }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture(),
    ) { success ->
        val uri = cameraPhotoUri
        if (success && uri != null) {
            onFilesSelected(listOf(uri))
        }
        cameraPhotoUri = null
    }

    // Creates a fresh temp-file URI and fires the camera. Shared by the just-granted-permission
    // callback and the already-have-permission path so the capture flow lives in exactly one place.
    val launchCamera: () -> Unit = {
        val photoFile = createCameraPhotoFile(context)
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            photoFile,
        )
        cameraPhotoUri = uri
        cameraLauncher.launch(uri)
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { isGranted ->
        if (isGranted) {
            launchCamera()
        }
    }

    return remember(context) {
        ChatAttachmentActions(
            onAttachFiles = { filePickerLauncher.launch(arrayOf("*/*")) },
            onTakePhoto = {
                val hasCameraPermission = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.CAMERA,
                ) == PackageManager.PERMISSION_GRANTED
                if (hasCameraPermission) {
                    launchCamera()
                } else {
                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                }
            },
            onPickPhotos = {
                photoPickerLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                )
            },
        )
    }
}

/**
 * Creates a temporary file for the camera to write a photo into.
 * Stored in the app's cache directory under `camera_photos/` which is
 * registered in the FileProvider paths XML.
 */
private fun createCameraPhotoFile(context: Context): File {
    val cameraDir = File(context.cacheDir, "camera_photos")
    if (!cameraDir.exists()) {
        cameraDir.mkdirs()
    }
    return File.createTempFile("photo_", ".jpg", cameraDir)
}
