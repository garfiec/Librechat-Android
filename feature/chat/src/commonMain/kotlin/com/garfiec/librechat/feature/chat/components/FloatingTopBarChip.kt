package com.garfiec.librechat.feature.chat.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/**
 * The diameter of a circular floating top-bar control chip. Shared so the bar's height
 * measurement and the chips themselves stay in sync.
 */
internal val FloatingBarChipSize = 44.dp

/**
 * A solid, outlined chip that backs each floating chat top-bar control. Uses the same fill and
 * border as the bottom composer's input box ([ChatInputDefaults]) so the floating header controls
 * read as the same family of surfaces — legible over arbitrary chat content scrolling behind them.
 */
@Composable
internal fun FloatingBarChip(
    modifier: Modifier = Modifier,
    shape: Shape = CircleShape,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val color = ChatInputDefaults.containerColor
    val border = BorderStroke(1.dp, ChatInputDefaults.borderColor)
    if (onClick != null) {
        Surface(onClick = onClick, shape = shape, color = color, border = border, modifier = modifier, content = content)
    } else {
        Surface(shape = shape, color = color, border = border, modifier = modifier, content = content)
    }
}

/** A circular [FloatingBarChip] wrapping a centered icon — the bar's hamburger/options control. */
@Composable
internal fun FloatingBarIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurface,
) {
    FloatingBarChip(onClick = onClick, modifier = modifier.size(FloatingBarChipSize)) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = tint,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/**
 * Absorbs pointer events that land in the floating bar's empty regions (the scrim gaps between
 * chips) so taps and drags there don't fall through to chat content scrolling behind the bar.
 * Chips inside the bar still receive their own events — children are dispatched first, so only
 * events they leave unconsumed are swallowed here.
 */
internal fun Modifier.consumeFloatingBarTouches(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            awaitPointerEvent().changes.forEach { it.consume() }
        }
    }
}

/**
 * Top-down scrim painted behind the floating top bar (covering the status-bar region). Fades from
 * a mostly-opaque surface tint at the very top to transparent, so chat content scrolling up behind
 * the bar reads as gently dimmed rather than hidden by a solid app bar.
 */
@Composable
internal fun chatTopBarScrim(): Brush {
    val surface = MaterialTheme.colorScheme.surface
    return remember(surface) {
        Brush.verticalGradient(
            colors = listOf(
                surface.copy(alpha = 0.92f),
                surface.copy(alpha = 0.55f),
                Color.Transparent,
            ),
        )
    }
}
