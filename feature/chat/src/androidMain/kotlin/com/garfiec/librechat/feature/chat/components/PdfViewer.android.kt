package com.garfiec.librechat.feature.chat.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.garfiec.librechat.core.ui.pdf.PdfDocumentHolder
import com.garfiec.librechat.feature.chat.resources.Res
import com.garfiec.librechat.feature.chat.resources.pdf_page_failed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource

/** Per-page render outcome. [Loading] and [Failed] are distinguished so a failed page can show an
 *  inline indicator instead of a silent blank gap. */
private sealed interface PageRender {
    data object Loading : PageRender
    data object Failed : PageRender
    data class Ready(val bitmap: ImageBitmap) : PageRender
}

/**
 * Android PDF surface backed by [android.graphics.pdf.PdfRenderer]. The bytes are staged to a
 * cache file (PdfRenderer needs a seekable [ParcelFileDescriptor]), then each page is rendered to a
 * bitmap on demand as it scrolls into view — off-screen pages are disposed by the [LazyColumn], so
 * memory tracks the visible window rather than the whole document.
 *
 * Pinch-zoom is per page and gated to multi-touch (see the second `pointerInput`) so a one-finger
 * drag still scrolls the list.
 */
@Composable
actual fun PdfViewer(bytes: ByteArray, onRenderError: () -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val currentOnRenderError by rememberUpdatedState(onRenderError)

    // The producer owns the holder's lifecycle: it publishes the instance it created and closes that
    // same instance via awaitDispose. (A DisposableEffect keyed on the delegated holder would read
    // the *live* value at dispose time and close the just-created holder on the null→holder swap.)
    // NonCancellable so a create() that finishes after the composition scrolled away is still
    // published-or-closed rather than leaking its fd/renderer.
    val holder by produceState<PdfDocumentHolder?>(null, bytes) {
        val fresh = withContext(Dispatchers.IO + NonCancellable) {
            PdfDocumentHolder.create(context, bytes)
        }
        value = fresh
        if (fresh == null) currentOnRenderError()
        awaitDispose { fresh?.close() }
    }

    val doc = holder ?: return
    LazyColumn(modifier = modifier.fillMaxSize()) {
        items(count = doc.pageCount, key = { it }, contentType = { "pdf_page" }) { index ->
            PdfPage(doc = doc, index = index)
        }
    }
}

/**
 * One page, fit to width, with per-page pinch-zoom + pan. The zoom gesture is gated to multi-touch
 * (see the second `pointerInput`) so a one-finger drag is left unconsumed and the enclosing
 * [LazyColumn] scrolls; two fingers zoom/pan the page. Double-tap resets. Pan is clamped so the page
 * can't be dragged off its own bounds.
 */
@Composable
private fun PdfPage(doc: PdfDocumentHolder, index: Int) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
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

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(aspect)
            .onSizeChanged { size = it }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offsetX
                translationY = offsetY
            }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = {
                    scale = 1f
                    offsetX = 0f
                    offsetY = 0f
                })
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.size >= 2) {
                            scale = (scale * event.calculateZoom()).coerceIn(1f, 5f)
                            if (scale > 1f) {
                                val pan = event.calculatePan()
                                val maxX = (size.width * (scale - 1f)) / 2f
                                val maxY = (size.height * (scale - 1f)) / 2f
                                offsetX = (offsetX + pan.x).coerceIn(-maxX, maxX)
                                offsetY = (offsetY + pan.y).coerceIn(-maxY, maxY)
                            } else {
                                offsetX = 0f
                                offsetY = 0f
                            }
                            // Consume only the multi-touch gesture so single-finger scroll passes through.
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        when (val r = render) {
            is PageRender.Ready -> Image(
                bitmap = r.bitmap,
                contentDescription = null,
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
}
