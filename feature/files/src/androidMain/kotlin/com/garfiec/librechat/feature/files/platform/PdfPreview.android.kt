package com.garfiec.librechat.feature.files.platform

import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import co.touchlab.kermit.Logger
import com.garfiec.librechat.core.ui.pdf.PdfDocumentHolder
import com.garfiec.librechat.core.ui.pdf.PdfPageContent
import com.garfiec.librechat.feature.files.FilePreviewDisplayData
import com.garfiec.librechat.feature.files.resources.*
import com.garfiec.librechat.feature.files.resources.Res
import com.garfiec.librechat.feature.files.screen.InfoRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

private sealed interface PdfLoadState {
    data object Loading : PdfLoadState
    data class Success(val doc: PdfDocumentHolder) : PdfLoadState

    /** [detail] carries the underlying cause (e.g. an HTTP error message) when one is known. */
    data class Error(val message: StringResource, val detail: String? = null) : PdfLoadState
}

@Composable
actual fun PdfPreview(
    file: FilePreviewDisplayData,
    onDownloadFile: (suspend (fileId: String, userId: String?) -> ByteArray?)?,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val currentOnDownloadFile by rememberUpdatedState(onDownloadFile)

    // The producer owns the holder's lifecycle: it closes the same instance it published, via
    // awaitDispose. The download stays cancellable; create() is NonCancellable so a create
    // finishing after dismissal is still closed rather than leaking its fd/renderer.
    val loadState by produceState<PdfLoadState>(PdfLoadState.Loading, file.fileId) {
        value = PdfLoadState.Loading
        val bytes = try {
            withContext(Dispatchers.IO) { currentOnDownloadFile?.invoke(file.fileId, file.userId) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(e) { "PdfPreview: download failed" }
            value = PdfLoadState.Error(Res.string.failed_to_download_pdf, e.message)
            return@produceState
        }
        if (bytes == null) {
            value = PdfLoadState.Error(Res.string.failed_to_download_pdf)
            return@produceState
        }
        val doc = withContext(Dispatchers.IO + NonCancellable) {
            PdfDocumentHolder.create(context, bytes)
        }
        if (doc == null) {
            value = PdfLoadState.Error(Res.string.failed_to_render_pdf)
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
                message = state.message,
                detail = state.detail,
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
            Column {
                PdfPageContent(
                    doc = doc,
                    index = index,
                    contentDescription = stringResource(Res.string.page_cd, index + 1, filename),
                )
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
    }
}

@Composable
private fun PdfErrorFallback(
    file: FilePreviewDisplayData,
    message: StringResource,
    detail: String?,
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
                    text = stringResource(message),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )

                detail?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }

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
