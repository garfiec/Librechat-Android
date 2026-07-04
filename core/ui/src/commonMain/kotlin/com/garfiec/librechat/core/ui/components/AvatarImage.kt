package com.garfiec.librechat.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

/** Default user avatar background matching the official web app. */
private val UserAvatarBlue = Color(0xFF7989FF)

@Composable
fun AvatarImage(
    imageUrl: String?,
    modifier: Modifier = Modifier,
    size: Dp = 32.dp,
    fallbackText: String = "?",
    fallbackIconPainter: Painter? = null,
    fallbackBackgroundColor: Color? = null,
    showPersonIcon: Boolean = false,
    tintIcon: Boolean = false,
    contentDescription: String? = "$fallbackText avatar",
) {
    if (imageUrl != null) {
        AsyncImage(
            model = imageUrl,
            contentDescription = contentDescription,
            modifier = modifier.size(size).clip(CircleShape),
            contentScale = ContentScale.Crop,
        )
    } else if (showPersonIcon) {
        // User avatar fallback: person icon with blue background
        Box(
            modifier = modifier
                .size(size)
                .clip(CircleShape)
                .background(UserAvatarBlue),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Person,
                contentDescription = contentDescription,
                modifier = Modifier.size(size * 0.6f),
                tint = Color.White,
            )
        }
    } else if (fallbackIconPainter != null) {
        // Provider icon fallback: painter with themed background
        Box(
            modifier = modifier
                .size(size)
                .clip(CircleShape)
                .background(fallbackBackgroundColor ?: MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = fallbackIconPainter,
                contentDescription = contentDescription,
                modifier = Modifier.size(size * 0.7f),
                tint = if (tintIcon) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    Color.Unspecified
                },
            )
        }
    } else {
        // Letter fallback (no photo). When the caller supplies a background (e.g. a stable
        // per-account color from avatarColorForSeed) pick black/white text by its luminance;
        // otherwise fall back to the theme's primaryContainer pair.
        val background = fallbackBackgroundColor ?: MaterialTheme.colorScheme.primaryContainer
        val foreground = if (fallbackBackgroundColor != null) {
            if (background.luminance() > 0.5f) Color.Black else Color.White
        } else {
            MaterialTheme.colorScheme.onPrimaryContainer
        }
        Box(
            modifier = modifier
                .size(size)
                .clip(CircleShape)
                .background(background),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = fallbackText.take(1).uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = foreground,
            )
        }
    }
}
