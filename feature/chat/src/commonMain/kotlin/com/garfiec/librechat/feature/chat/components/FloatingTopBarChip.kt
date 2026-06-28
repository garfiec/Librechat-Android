package com.garfiec.librechat.feature.chat.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
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

/**
 * The shared frame for a floating-bar content bubble: a [FloatingBarChip] sized to the bar's chip
 * height with its [content] vertically centered. [fillWidth] expands the chip across the available
 * region (the FILL alignment); otherwise it hugs its content. [contentModifier] is applied to the
 * centered interior so callers can attach gestures spanning the whole bubble.
 */
@Composable
internal fun FloatingBarContentChip(
    fillWidth: Boolean,
    modifier: Modifier = Modifier,
    contentModifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    FloatingBarChip(
        modifier = modifier.height(FloatingBarChipSize)
            .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier),
        onClick = onClick,
    ) {
        Box(
            modifier = Modifier.fillMaxHeight().then(contentModifier),
            contentAlignment = Alignment.Center,
        ) {
            content()
        }
    }
}

/**
 * A [FloatingBarContentChip] wrapping a single centered, ellipsized label — the bar's title/model
 * content bubble. Shared so the two content modes render identically. A tap is wired through
 * [onClick] as a proper button (model selector); [onLongClick] is wired via a tap-gesture detector
 * plus a long-click semantics action (the in-place title edit), so it adds neither a no-op click
 * role nor a ripple to a label whose only action is long-press. The detector is keyed on `Unit` and
 * reads the latest callback via [rememberUpdatedState], so an unstable caller lambda doesn't restart
 * the gesture coroutine on every recomposition.
 */
@Composable
internal fun FloatingBarLabelChip(
    text: String,
    modifier: Modifier = Modifier,
    fillWidth: Boolean = false,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
) {
    val latestLongClick by rememberUpdatedState(onLongClick)
    val contentModifier = if (onLongClick != null) {
        Modifier
            .pointerInput(Unit) { detectTapGestures(onLongPress = { latestLongClick?.invoke() }) }
            .semantics { onLongClick(label = onLongClickLabel) { latestLongClick?.invoke(); true } }
    } else {
        Modifier
    }
    FloatingBarContentChip(
        fillWidth = fillWidth,
        modifier = modifier,
        onClick = onClick,
        contentModifier = contentModifier,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
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
