package com.garfiec.librechat.core.ui.pdf

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.garfiec.librechat.core.ui.resources.Res
import com.garfiec.librechat.core.ui.resources.pdf_page_failed
import org.jetbrains.compose.resources.stringResource

/** Per-page render outcome. [Loading] and [Failed] are distinguished so a failed page can show an
 *  inline indicator instead of a silent blank gap. */
private sealed interface PageRender {
    data object Loading : PageRender
    data object Failed : PageRender
    data class Ready(val bitmap: ImageBitmap) : PageRender
}

/**
 * One PDF page from [doc], rendered on demand when it scrolls into a LazyColumn's window and freed
 * when it scrolls out, so memory tracks the visible window rather than the whole document. Sized to
 * the page's aspect ratio (a placeholder until the first render reports the real one) so the scroll
 * position stays stable.
 *
 * [modifier] is appended after the size/aspect modifiers, so callers can attach draw-time
 * transforms and gesture handling in the same order they would on their own page Box.
 */
@Composable
fun PdfPageContent(
    doc: PdfDocumentHolder,
    index: Int,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    var size by remember { mutableStateOf(IntSize.Zero) }

    val widthPx = size.width
    val renderState = produceState<PageRender>(PageRender.Loading, doc, index, widthPx) {
        // A width-change restart supersedes the previous bitmap. Blank the display first — this
        // runs in the frame's effects, before draw — then free the old pixels; leaving them to GC
        // lets native ARGB_8888 buffers pile up across fold/rotate re-renders.
        val superseded = (value as? PageRender.Ready)?.bitmap
        value = PageRender.Loading
        superseded?.asAndroidBitmap()?.recycle()
        if (widthPx > 0) {
            value = doc.renderPage(index, widthPx)?.let { PageRender.Ready(it) } ?: PageRender.Failed
        }
    }

    // Recycle when the page leaves composition (scrolls out of the window). Reads the State
    // directly — not a value captured at the last recomposition — so a bitmap that landed after
    // the final recomposition is still freed. Between this, the superseded-recycle above, and
    // renderPage freeing a render its caller's cancellation discarded, every rendered bitmap has
    // exactly one owner.
    DisposableEffect(renderState) {
        onDispose { (renderState.value as? PageRender.Ready)?.bitmap?.asAndroidBitmap()?.recycle() }
    }

    val render = renderState.value
    val bitmap = (render as? PageRender.Ready)?.bitmap

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
            .then(modifier),
        contentAlignment = Alignment.Center,
    ) {
        when (render) {
            is PageRender.Ready -> Image(
                bitmap = render.bitmap,
                contentDescription = contentDescription,
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
