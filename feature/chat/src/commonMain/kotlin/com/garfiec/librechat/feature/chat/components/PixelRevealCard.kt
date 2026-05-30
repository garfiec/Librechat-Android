package com.garfiec.librechat.feature.chat.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.hypot
import kotlin.random.Random

private const val MAX_SIZE_INTEGER = 4f
private const val MIN_SIZE = 1f

/**
 * Compose port of the web client's `PixelCard` (packages/client/src/components/PixelCard.tsx).
 *
 * A grid of tiny squares that "reveal" from the center outward as [progress] rises 0→1:
 * each pixel has a distance-based activation threshold, grows in once progress passes it,
 * then shimmers. The whole field fades out as progress approaches 1. Used as the image-gen
 * placeholder so the in-progress state matches web instead of a plain spinner.
 */
@Composable
internal fun PixelRevealCard(
    progress: Float,
    colors: List<Color>,
    modifier: Modifier = Modifier,
    gap: Dp = 4.dp,
    randomness: Float = 0.6f,
    speed: Int = 35,
) {
    var sizePx by remember { mutableStateOf(IntSize.Zero) }
    val gapPx = with(LocalDensity.current) { gap.toPx() }.coerceAtLeast(4f)
    val effectiveSpeed = speed.coerceIn(0, 100) * 0.001f

    val pixels = remember(sizePx, gapPx, randomness, colors) {
        if (sizePx.width == 0 || sizePx.height == 0) {
            emptyList()
        } else {
            buildPixels(sizePx.width, sizePx.height, gapPx, colors, randomness, effectiveSpeed)
        }
    }

    val currentProgress by rememberUpdatedState(progress)
    var frame by remember { mutableLongStateOf(0L) }

    LaunchedEffect(pixels) {
        if (pixels.isEmpty()) return@LaunchedEffect
        while (true) {
            withFrameMillis { now ->
                for (p in pixels) {
                    p.appearWithProgress(currentProgress)
                }
                frame = now
            }
        }
    }

    // Fade the whole field out as it completes (web: opacity 1→0 over progress 0.9→1.0).
    val fieldAlpha = if (progress >= 0.9f) {
        (1f - (progress - 0.9f) / 0.1f).coerceIn(0f, 1f)
    } else {
        1f
    }

    Canvas(modifier.onSizeChanged { sizePx = it }) {
        frame // read to invalidate the draw each animation frame
        if (fieldAlpha <= 0f) return@Canvas
        for (p in pixels) {
            if (p.size <= 0f) continue
            val offset = MAX_SIZE_INTEGER * 0.5f - p.size * 0.5f
            drawRect(
                color = p.color.copy(alpha = p.color.alpha * fieldAlpha),
                topLeft = Offset(p.x + offset, p.y + offset),
                size = Size(p.size, p.size),
            )
        }
    }
}

private fun buildPixels(
    width: Int,
    height: Int,
    gap: Float,
    colors: List<Color>,
    randomness: Float,
    effectiveSpeed: Float,
): List<Pixel> {
    val list = ArrayList<Pixel>()
    val cx = width / 2f
    val cy = height / 2f
    val maxDist = hypot(cx, cy).coerceAtLeast(1f)
    var x = 0f
    while (x < width) {
        var y = 0f
        while (y < height) {
            val color = colors[Random.nextInt(colors.size)]
            val distNorm = hypot(x - cx, y - cy) / maxDist
            val threshold = (distNorm * (1f - randomness) + Random.nextFloat() * randomness)
                .coerceIn(0f, 1f)
            val delay = distNorm * maxDist
            val pxSpeed = (0.1f + Random.nextFloat() * 0.8f) * effectiveSpeed
            list.add(Pixel(x, y, color, pxSpeed, delay, threshold, (width + height).toFloat()))
            y += gap
        }
        x += gap
    }
    return list
}

/** A single animated pixel, ported from the web `Pixel` class. */
private class Pixel(
    val x: Float,
    val y: Float,
    val color: Color,
    private val speed: Float,
    private val delay: Float,
    private val activationThreshold: Float,
    canvasExtent: Float,
) {
    var size = 0f
    private val sizeStep = Random.nextFloat() * 0.4f
    private val maxSize = MIN_SIZE + Random.nextFloat() * (MAX_SIZE_INTEGER - MIN_SIZE)
    private var counter = 0f
    private val counterStep = Random.nextFloat() * 4f + canvasExtent * 0.01f
    private var isReverse = false
    private var isShimmer = false

    fun appearWithProgress(progress: Float) {
        if (progress - activationThreshold <= 0f) return
        if (counter <= delay) {
            counter += counterStep
            return
        }
        if (size >= maxSize) isShimmer = true
        if (isShimmer) shimmer() else size += sizeStep
    }

    private fun shimmer() {
        if (size >= maxSize) {
            isReverse = true
        } else if (size <= MIN_SIZE) {
            isReverse = false
        }
        size += if (isReverse) -speed else speed
    }
}
