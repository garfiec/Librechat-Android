package com.garfiec.librechat.feature.chat.components

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.toBitmap
import com.garfiec.librechat.feature.chat.resources.*
import com.garfiec.librechat.feature.chat.resources.Res
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import java.io.File

@Composable
actual fun FullscreenImageViewer(
    imageUrl: String,
    onDismiss: () -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val saveImageToGallery: () -> Unit = remember(imageUrl) {
        {
            scope.launch {
                try {
                    val request = ImageRequest.Builder(context)
                        .data(imageUrl)
                        .build()
                    val result = SingletonImageLoader.get(context).execute(request)
                    val bitmap = result.image?.toBitmap()
                    if (bitmap == null) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "Failed to load image", Toast.LENGTH_SHORT)
                                .show()
                        }
                        return@launch
                    }

                    val fileName = "librechat_${System.currentTimeMillis()}.png"

                    try {
                        withContext(Dispatchers.IO) {
                            val contentValues = ContentValues().apply {
                                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                    put(
                                        MediaStore.Images.Media.RELATIVE_PATH,
                                        Environment.DIRECTORY_PICTURES + "/LibreChat",
                                    )
                                    put(MediaStore.Images.Media.IS_PENDING, 1)
                                }
                            }

                            val uri = context.contentResolver.insert(
                                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                                contentValues,
                            )

                            if (uri == null) {
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(
                                        context,
                                        "Failed to save image",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                                return@withContext
                            }

                            context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                                bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
                            }

                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                val updateValues = ContentValues().apply {
                                    put(MediaStore.Images.Media.IS_PENDING, 0)
                                }
                                context.contentResolver.update(uri, updateValues, null, null)
                            }
                        }
                    } finally {
                        bitmap.recycle()
                    }

                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Image saved to gallery", Toast.LENGTH_SHORT)
                            .show()
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            context,
                            "Failed to save image: ${e.localizedMessage ?: ""}",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            }
        }
    }

    var pendingSaveAfterPermission by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { isGranted ->
        if (isGranted && pendingSaveAfterPermission) {
            pendingSaveAfterPermission = false
            saveImageToGallery()
        } else if (!isGranted) {
            pendingSaveAfterPermission = false
            Toast.makeText(context, "Storage permission is required to save images", Toast.LENGTH_SHORT)
                .show()
        }
    }

    val onSaveClick: () -> Unit = remember(imageUrl) {
        {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveImageToGallery()
            } else {
                val permission = Manifest.permission.WRITE_EXTERNAL_STORAGE
                if (ContextCompat.checkSelfPermission(context, permission) ==
                    PackageManager.PERMISSION_GRANTED
                ) {
                    saveImageToGallery()
                } else {
                    pendingSaveAfterPermission = true
                    permissionLauncher.launch(permission)
                }
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.9f)),
        ) {
            var scale by remember { mutableFloatStateOf(1f) }
            var offset by remember { mutableStateOf(Offset.Zero) }

            AsyncImage(
                model = imageUrl,
                contentDescription = stringResource(Res.string.cd_fullscreen_image),
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y,
                    )
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(0.5f, 5f)
                            offset = Offset(
                                x = offset.x + pan.x,
                                y = offset.y + pan.y,
                            )
                        }
                    },
            )

            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(16.dp)
                    .size(40.dp),
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = Color.Black.copy(alpha = 0.5f),
                    contentColor = Color.White,
                ),
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(Res.string.cd_close),
                )
            }

            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(16.dp),
            ) {
                IconButton(
                    onClick = { onSaveClick() },
                    modifier = Modifier.size(40.dp),
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = Color.Black.copy(alpha = 0.5f),
                        contentColor = Color.White,
                    ),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.SaveAlt,
                        contentDescription = stringResource(Res.string.cd_save_to_device),
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                IconButton(
                    onClick = {
                        scope.launch {
                            try {
                                val request = ImageRequest.Builder(context)
                                    .data(imageUrl)
                                    .build()
                                val result = SingletonImageLoader.get(context).execute(request)
                                val bitmap = result.image?.toBitmap()
                                    ?: return@launch

                                val imagesDir = File(context.cacheDir, "shared_images")
                                val imageFile = File(
                                    imagesDir,
                                    "shared_image_${System.currentTimeMillis()}.png",
                                )
                                try {
                                    withContext(Dispatchers.IO) {
                                        imagesDir.mkdirs()
                                        imageFile.outputStream().use { out ->
                                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                                        }
                                    }
                                } finally {
                                    bitmap.recycle()
                                }

                                val contentUri = FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    imageFile,
                                )

                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    putExtra(Intent.EXTRA_STREAM, contentUri)
                                    type = "image/png"
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(shareIntent, null))
                            } catch (_: Exception) {
                                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                    putExtra(Intent.EXTRA_TEXT, imageUrl)
                                    type = "text/plain"
                                }
                                context.startActivity(Intent.createChooser(sendIntent, null))
                            }
                        }
                    },
                    modifier = Modifier.size(40.dp),
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = Color.Black.copy(alpha = 0.5f),
                        contentColor = Color.White,
                    ),
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = stringResource(Res.string.cd_share_image),
                    )
                }
            }
        }
    }
}
