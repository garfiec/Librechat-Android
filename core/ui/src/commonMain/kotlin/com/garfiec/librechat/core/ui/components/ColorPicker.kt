package com.garfiec.librechat.core.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

// Constant gradients hoisted out of the per-frame Canvas draw scope (see core/ui rule 13).
// Both span the full draw size at paint time, so they carry no size dependency.
private val HueSpectrumBrush = Brush.horizontalGradient(
    listOf(0f, 60f, 120f, 180f, 240f, 300f, 360f).map { Color.hsv(it, 1f, 1f) },
)
private val ValueOverlayBrush = Brush.verticalGradient(listOf(Color.Transparent, Color.Black))

// Static clip shapes hoisted out of the Canvas modifier chains (core/ui rule 13).
private val PanelShape = RoundedCornerShape(12.dp)
private val SliderShape = RoundedCornerShape(14.dp)

/**
 * Stateless saturation/value selection panel. Renders a white -> hue horizontal
 * gradient overlaid with a transparent -> black vertical gradient, plus a thumb at
 * the current [saturation]/[value]. Reports new values as the user taps or drags.
 *
 * @param hue current hue in degrees (0..360), drives the panel's base color
 * @param saturation current saturation (0..1)
 * @param value current value/brightness (0..1)
 * @param onChange invoked with the new (saturation, value) on tap/drag
 */
@Composable
fun SaturationValuePanel(
    hue: Float,
    saturation: Float,
    value: Float,
    onChange: (saturation: Float, value: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    fun report(position: Offset, width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val s = (position.x / width).coerceIn(0f, 1f)
        val v = 1f - (position.y / height).coerceIn(0f, 1f)
        onChange(s, v)
    }

    val hueBrush = remember(hue) {
        Brush.horizontalGradient(listOf(Color.White, Color.hsv(hue, 1f, 1f)))
    }
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(200.dp)
            .clip(PanelShape)
            .pointerInput(Unit) {
                detectTapGestures { offset -> report(offset, size.width, size.height) }
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset -> report(offset, size.width, size.height) },
                ) { change, _ -> report(change.position, size.width, size.height) }
            },
    ) {
        drawRect(hueBrush)
        drawRect(ValueOverlayBrush)

        val cx = saturation * size.width
        val cy = (1f - value) * size.height
        val center = Offset(cx, cy)
        val radius = 9.dp.toPx()
        drawCircle(Color.Black, radius = radius, center = center, style = Stroke(width = 3.dp.toPx()))
        drawCircle(Color.White, radius = radius, center = center, style = Stroke(width = 2.dp.toPx()))
    }
}

/**
 * Stateless hue selection slider rendering the full hue spectrum with a thumb at
 * the current [hue]. Reports the new hue (0..360) on tap/drag.
 */
@Composable
fun HueSlider(
    hue: Float,
    onHueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    fun report(position: Offset, width: Int) {
        if (width <= 0) return
        onHueChange((position.x / width).coerceIn(0f, 1f) * 360f)
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(28.dp)
            .clip(SliderShape)
            .pointerInput(Unit) {
                detectTapGestures { offset -> report(offset, size.width) }
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset -> report(offset, size.width) },
                ) { change, _ -> report(change.position, size.width) }
            },
    ) {
        drawRect(HueSpectrumBrush)

        val cx = (hue / 360f) * size.width
        val cy = size.height / 2f
        val radius = size.height / 2f - 2.dp.toPx()
        drawCircle(Color.Black, radius = radius, center = Offset(cx, cy), style = Stroke(width = 3.dp.toPx()))
        drawCircle(Color.White, radius = radius, center = Offset(cx, cy), style = Stroke(width = 2.dp.toPx()))
    }
}

/** Decomposes a [Color] into HSV: (hue 0..360, saturation 0..1, value 0..1). */
fun Color.toHsv(): Triple<Float, Float, Float> {
    val r = red
    val g = green
    val b = blue
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val delta = max - min
    val hue = when {
        delta == 0f -> 0f
        max == r -> 60f * (((g - b) / delta) % 6f)
        max == g -> 60f * (((b - r) / delta) + 2f)
        else -> 60f * (((r - g) / delta) + 4f)
    }.let { if (it < 0f) it + 360f else it }
    val saturation = if (max == 0f) 0f else delta / max
    return Triple(hue, saturation, max)
}

/** Builds an opaque [Color] from HSV components. */
fun hsvColor(hue: Float, saturation: Float, value: Float): Color =
    Color.hsv(hue.coerceIn(0f, 360f), saturation.coerceIn(0f, 1f), value.coerceIn(0f, 1f))

/** Formats the opaque RGB channels as an uppercase `#RRGGBB` string. */
fun Color.toHexString(): String {
    fun channel(c: Float) = (c * 255f).roundToInt().coerceIn(0, 255).toString(16).uppercase().padStart(2, '0')
    return "#${channel(red)}${channel(green)}${channel(blue)}"
}

/**
 * Parses a hex color string (`#RGB`, `#RRGGBB`, or `#RRGGBBAA` / without `#`) into an
 * opaque [Color], or null if malformed. Alpha is forced opaque since accent seeds are solid.
 */
fun parseHexColor(input: String): Color? {
    val hex = input.trim().removePrefix("#")
    val rrggbb = when (hex.length) {
        3 -> hex.map { "$it$it" }.joinToString("")
        6 -> hex
        8 -> hex.substring(0, 6)
        else -> return null
    }
    val rgb = rrggbb.toLongOrNull(16)?.toInt() ?: return null
    return Color(0xFF000000.toInt() or rgb)
}
