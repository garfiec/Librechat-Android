package com.garfiec.librechat.feature.files.platform

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import co.touchlab.kermit.Logger
import com.garfiec.librechat.feature.files.FilePreviewDisplayData
import com.garfiec.librechat.feature.files.resources.*
import com.garfiec.librechat.feature.files.resources.Res
import com.garfiec.librechat.feature.files.screen.InfoRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import java.io.File

private sealed interface PdfLoadState {
    data object Loading : PdfLoadState
    data class Success(val pages: List<Bitmap>) : PdfLoadState
    data class Error(val message: String) : PdfLoadState
}

@Composable
actual fun PdfPreview(
    file: FilePreviewDisplayData,
    onDownloadFile: (suspend (fileId: String, userId: String?) -> ByteArray?)?,
    modifier: Modifier,
) {
    val context = LocalContext.current
    var loadState by remember { mutableStateOf<PdfLoadState>(PdfLoadState.Loading) }
    var tempFile by remember { mutableStateOf<File?>(null) }

    DisposableEffect(file.fileId) {
        onDispose {
            tempFile?.delete()
            val currentState = loadState
            if (currentState is PdfLoadState.Success) {
                currentState.pages.forEach { bitmap ->
                    if (!bitmap.isRecycled) {
                        bitmap.recycle()
                    }
                }
            }
        }
    }

    LaunchedEffect(file.fileId) {
        loadState = PdfLoadState.Loading
        try {
            // Download, disk write, and PdfRenderer page rendering are all blocking.
            // Run them off the Main thread; only the resulting state lands on Main.
            val result = withContext(Dispatchers.IO) {
                val bytes = onDownloadFile?.invoke(file.fileId, file.userId)
                    ?: return@withContext PdfLoadState.Error("Failed to download PDF")

                val pdfPreviewDir = File(context.cacheDir, "pdf_preview").apply { mkdirs() }
                val pdfTempFile = File(pdfPreviewDir, "pdf_preview_${file.fileId}.pdf")
                pdfTempFile.writeBytes(bytes)
                tempFile = pdfTempFile

                val fd = ParcelFileDescriptor.open(
                    pdfTempFile,
                    ParcelFileDescriptor.MODE_READ_ONLY,
                )
                val renderer = PdfRenderer(fd)
                val pageCount = renderer.pageCount
                Logger.d { "PdfPreview: opened PDF with $pageCount pages" }

                val maxPages = minOf(pageCount, 50)
                val bitmaps = mutableListOf<Bitmap>()
                val displayMetrics = context.resources.displayMetrics
                val targetWidth = displayMetrics.widthPixels

                for (i in 0 until maxPages) {
                    val page = renderer.openPage(i)
                    val scale = targetWidth.toFloat() / page.width
                    val bitmapWidth = targetWidth
                    val bitmapHeight = (page.height * scale).toInt()

                    val bitmap = Bitmap.createBitmap(
                        bitmapWidth,
                        bitmapHeight,
                        Bitmap.Config.ARGB_8888,
                    )
                    bitmap.eraseColor(Color.WHITE)
                    page.render(
                        bitmap,
                        null,
                        null,
                        PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY,
                    )
                    page.close()
                    bitmaps.add(bitmap)
                }

                renderer.close()
                fd.close()

                if (pageCount > maxPages) {
                    Logger.d { "PdfPreview: showing first $maxPages of $pageCount pages" }
                }
                PdfLoadState.Success(bitmaps)
            }

            loadState = result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(e) { "PdfPreview: failed to render PDF" }
            loadState = PdfLoadState.Error(
                e.message ?: "Failed to render PDF",
            )
        }
    }

    when (val state = loadState) {
        is PdfLoadState.Loading -> {
            Box(
                modifier = modifier,
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    CircularProgressIndicator()
                    Text(
                        text = stringResource(Res.string.loading_pdf),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        is PdfLoadState.Error -> {
            PdfErrorFallback(
                file = file,
                errorMessage = state.message,
                modifier = modifier,
            )
        }
        is PdfLoadState.Success -> {
            PdfPagesList(
                pages = state.pages,
                filename = file.filename,
                modifier = modifier,
            )
        }
    }
}

@Composable
private fun PdfPagesList(
    pages: List<Bitmap>,
    filename: String,
    modifier: Modifier = Modifier,
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    val transformableState = rememberTransformableState { zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(0.5f, 5f)
        offset = Offset(
            x = offset.x + panChange.x,
            y = offset.y + panChange.y,
        )
    }

    LazyColumn(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            }
            .transformable(state = transformableState),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        itemsIndexed(
            items = pages,
            key = { index, _ -> "page_$index" },
        ) { index, bitmap ->
            Column {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = stringResource(Res.string.page_cd, index + 1, filename),
                    modifier = Modifier.fillMaxWidth(),
                    contentScale = ContentScale.FillWidth,
                )
                Text(
                    text = stringResource(Res.string.page_of, index + 1, pages.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun PdfErrorFallback(
    file: FilePreviewDisplayData,
    errorMessage: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.PictureAsPdf,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.error,
                )

                Text(
                    text = file.filename,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                Text(
                    text = stringResource(Res.string.could_not_render_pdf),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )

                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    InfoRow(stringResource(Res.string.info_type), file.type)
                    InfoRow(stringResource(Res.string.info_size), file.formattedSize)
                    file.createdAt?.let { InfoRow(stringResource(Res.string.info_created), it) }
                    file.source?.let { InfoRow(stringResource(Res.string.info_source), it) }
                }
            }
        }
    }
}
