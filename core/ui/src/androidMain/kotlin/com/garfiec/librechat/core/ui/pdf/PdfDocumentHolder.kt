package com.garfiec.librechat.core.ui.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicIntegerArray

/** Placeholder aspect (≈ A4 portrait, 1/√2) used before a page's real dimensions are known. */
private const val DEFAULT_PAGE_ASPECT = 0.7071f

/**
 * Hard caps on a rendered page bitmap's dimensions in pixels. A page fit to a large-screen width
 * (unfolded foldable, tablet) or a pathological aspect could otherwise demand hundreds of MB across
 * the visible window and OOM. We downscale by a single factor so *both* dimensions stay under their
 * cap while the aspect is preserved, and let `ContentScale.FillWidth` upscale the smaller bitmap.
 */
private const val MAX_PAGE_BITMAP_WIDTH_PX = 2048
private const val MAX_PAGE_BITMAP_HEIGHT_PX = 8192

/**
 * Upper bound on the page count taken from the document. Per-page bookkeeping ([aspectRatios]) and
 * the caller's LazyColumn item count are O(pageCount), so a corrupt or crafted PDF claiming
 * millions of pages could otherwise allocate its way to an OOM before a single page renders.
 */
private const val MAX_PAGE_COUNT = 2000

/**
 * Owns the [PdfRenderer] and its backing fd. [renderPage] is serialized by a [Mutex] because
 * PdfRenderer allows only one open page at a time and the LazyColumn may render several pages
 * concurrently as they scroll in.
 */
class PdfDocumentHolder private constructor(
    private val fd: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
    val pageCount: Int,
) {
    private val mutex = Mutex()

    @Volatile private var closed = false
    private val tornDown = AtomicBoolean(false)

    // Holder-owned teardown scope (cancelled once teardown completes) rather than a fresh anonymous
    // CoroutineScope per close() with no lifecycle owner.
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Real per-page width/height ratios, filled lazily by renderPage. Float bits in an
    // AtomicIntegerArray so the render-thread write is visible to the main-thread aspectRatio() read
    // (a plain FloatArray gives no cross-thread visibility guarantee). 0 bits == unset.
    private val aspectRatios = AtomicIntegerArray(pageCount)

    /** width / height, for placeholder sizing before the bitmap renders (real value once rendered). */
    fun aspectRatio(index: Int): Float {
        if (index < 0 || index >= pageCount) return DEFAULT_PAGE_ASPECT
        return Float.fromBits(aspectRatios.get(index)).takeIf { it.isFinite() && it > 0f }
            ?: DEFAULT_PAGE_ASPECT
    }

    suspend fun renderPage(index: Int, widthPx: Int): ImageBitmap? {
        var rendered: Bitmap? = null
        try {
            return withContext(Dispatchers.IO) {
                runCatching {
                    mutex.withLock {
                        if (closed) return@withLock null
                        renderer.openPage(index).use { page ->
                            if (page.width <= 0 || page.height <= 0) return@use null
                            aspectRatios.set(index, (page.width.toFloat() / page.height).toRawBits())

                            // Fit to width, then downscale by one factor so neither dimension exceeds its cap
                            // (aspect preserved). FillWidth upscales the smaller bitmap back to the container.
                            val fitHeight = widthPx.toFloat() * page.height / page.width
                            val downscale = minOf(
                                1f,
                                MAX_PAGE_BITMAP_WIDTH_PX / widthPx.toFloat(),
                                MAX_PAGE_BITMAP_HEIGHT_PX / fitHeight,
                            )
                            val renderWidth = (widthPx * downscale).toInt().coerceAtLeast(1)
                            val renderHeight = (fitHeight * downscale).toInt().coerceAtLeast(1)

                            val bmp = Bitmap.createBitmap(renderWidth, renderHeight, Bitmap.Config.ARGB_8888)
                            rendered = bmp
                            // PDF pages are transparent where unpainted; fill white so text is legible on dark themes.
                            bmp.eraseColor(Color.WHITE)
                            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            bmp.asImageBitmap()
                        }
                    }
                }.getOrNull()
            }
        } catch (e: CancellationException) {
            // Prompt cancellation can discard a render that already completed on the IO thread —
            // the caller never receives the bitmap, so free it here instead of leaving it to GC.
            rendered?.recycle()
            throw e
        }
    }

    fun close() {
        // Idempotent: guard so a double-dispose doesn't launch teardown twice.
        if (!tornDown.compareAndSet(false, true)) return
        // Flag first so in-flight renders bail; do the actual teardown under the lock so we never
        // close the renderer out from under a page.render() that's mid-flight.
        closed = true
        ioScope.launch {
            mutex.withLock {
                runCatching { renderer.close() }
                runCatching { fd.close() }
            }
        }.invokeOnCompletion { ioScope.cancel() }
    }

    companion object {
        fun create(context: Context, bytes: ByteArray): PdfDocumentHolder? {
            val dir = File(context.cacheDir, "pdf_preview").apply { mkdirs() }
            val file = File.createTempFile("preview_", ".pdf", dir)
            var opened: ParcelFileDescriptor? = null
            return runCatching {
                file.writeBytes(bytes)
                val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                opened = fd
                // Unlink now: the fd keeps the data readable, and the on-disk entry is gone even if the
                // process is killed with the preview open — no orphaned cache files.
                file.delete()
                val renderer = PdfRenderer(fd)
                val pageCount = minOf(renderer.pageCount, MAX_PAGE_COUNT)
                if (renderer.pageCount > pageCount) {
                    Logger.w { "PdfDocumentHolder: capping ${renderer.pageCount}-page document at $pageCount pages" }
                }
                PdfDocumentHolder(fd, renderer, pageCount)
            }.onFailure { e ->
                Logger.e(e) { "PdfDocumentHolder.create failed" }
                runCatching { opened?.close() }
                runCatching { file.delete() }
            }.getOrNull()
        }
    }
}
