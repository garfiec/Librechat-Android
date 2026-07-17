package com.garfiec.librechat.feature.files.platform

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import co.touchlab.kermit.Logger
import com.garfiec.librechat.core.ui.pdf.PdfDocumentHolder
import com.garfiec.librechat.feature.files.FilePreviewDisplayData
import com.garfiec.librechat.feature.files.resources.*
import com.garfiec.librechat.feature.files.resources.Res
import com.garfiec.librechat.feature.files.screen.InfoRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource

private sealed interface PdfLoadState {
    data object Loading : PdfLoadState
    data class Success(val doc: PdfDocumentHolder) : PdfLoadState
    data class Error(val message: String) : PdfLoadState
}

/** Per-page render outcome. [Loading] and [Failed] are distinguished so a failed page can show an
 *  inline indicator instead of a silent blank gap. */
private sealed interface PageRender {
    data object Loading : PageRender
    data object Failed : PageRender
    data class Ready(val bitmap: ImageBitmap) : PageRender
}

@Composable
actual fun PdfPreview(
    file: FilePreviewDisplayData,
    onDownloadFile: (suspend (fileId: String, userId: String?) -> ByteArray?)?,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val currentOnDownloadFile by rememberUpdatedState(onDownloadFile)

    // The producer owns the holder's lifecycle: it publishes the instance it created and closes that
    // same instance via awaitDispose. Download is cancellable (leaving mid-download just stops it);
    // create() is NonCancellable so a create finishing after dismissal is still published-then-closed
    // rather than leaking its fd/renderer.
    val loadState by produceState<PdfLoadState>(PdfLoadState.Loading, file.fileId) {
        value = PdfLoadState.Loading
        val bytes = try {
            withContext(Dispatchers.IO) { currentOnDownloadFile?.invoke(file.fileId, file.userId) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(e) { "PdfPreview: download failed" }
            null
        }
        if (bytes == null) {
            value = PdfLoadState.Error("Failed to download PDF")
            return@produceState
        }
        val doc = withContext(Dispatchers.IO + NonCancellable) {
            PdfDocumentHolder.create(context, bytes)
        }
        if (doc == null) {
            value = PdfLoadState.Error("Failed to render PDF")
            return@produceState
        }
        value = PdfLoadState.Success(doc)
        awaitDispose { doc.close() }
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
                doc = state.doc,
                filename = file.filename,
                modifier = modifier,
            )
        }
    }
}

@Composable
private fun PdfPagesList(
    doc: PdfDocumentHolder,
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
        items(count = doc.pageCount, key = { it }, contentType = { "pdf_page" }) { index ->
            PdfPreviewPage(doc = doc, index = index, filename = filename)
        }
    }
}

/**
 * One page, rendered on demand as it scrolls into view and disposed when it scrolls out, so memory
 * tracks the visible window rather than the whole document. Sized to the page's aspect ratio (a
 * placeholder until the first render reports the real one) so the scroll position stays stable.
 */
@Composable
private fun PdfPreviewPage(doc: PdfDocumentHolder, index: Int, filename: String) {
    var size by remember { mutableStateOf(IntSize.Zero) }

    val widthPx = size.width
    val render by produceState<PageRender>(PageRender.Loading, doc, index, widthPx) {
        value = PageRender.Loading
        if (widthPx > 0) {
            value = doc.renderPage(index, widthPx)?.let { PageRender.Ready(it) } ?: PageRender.Failed
        }
    }
    val bitmap = (render as? PageRender.Ready)?.bitmap

    // Recycle the page bitmap's native memory when the page leaves composition (scrolls out of the
    // LazyColumn window); GC alone lets ARGB_8888 buffers pile up while the collector catches up.
    // rememberUpdatedState so the effect frees whichever bitmap is current at dispose time.
    val bitmapToRecycle by rememberUpdatedState(bitmap)
    DisposableEffect(Unit) {
        onDispose { bitmapToRecycle?.asAndroidBitmap()?.recycle() }
    }

    // Once the bitmap is in, size the box to its true aspect (drives relayout); until then use the
    // stored/placeholder ratio. Sanitize so Modifier.aspectRatio never sees 0 / NaN / ∞.
    val aspect = bitmap
        ?.let { it.width.toFloat() / it.height.toFloat() }
        ?.takeIf { it.isFinite() && it > 0f }
        ?: doc.aspectRatio(index)

    Column {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(aspect)
                .onSizeChanged { size = it },
            contentAlignment = Alignment.Center,
        ) {
            when (val r = render) {
                is PageRender.Ready -> Image(
                    bitmap = r.bitmap,
                    contentDescription = stringResource(Res.string.page_cd, index + 1, filename),
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.FillWidth,
                )
                is PageRender.Failed -> Text(
                    text = stringResource(Res.string.pdf_page_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
                is PageRender.Loading -> Unit // blank until the first render lands
            }
        }
        Text(
            text = stringResource(Res.string.page_of, index + 1, doc.pageCount),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
        )
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
