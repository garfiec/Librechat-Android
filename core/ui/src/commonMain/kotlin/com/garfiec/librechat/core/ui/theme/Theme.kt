package com.garfiec.librechat.core.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.materialkolor.PaletteStyle
import com.materialkolor.rememberDynamicColorScheme

/**
 * Returns a platform-specific dynamic color scheme if available, or null to fall back
 * to the LibreChat color scheme. On Android 12+, returns wallpaper-based Material You colors.
 */
@Composable
expect fun platformColorScheme(darkTheme: Boolean, dynamicColor: Boolean): ColorScheme?

/**
 * Whether the platform supports wallpaper-based Material You dynamic color.
 * Android 12+ returns true; iOS returns false. Used to gate the "use wallpaper
 * colors" setting and the [LibreChatTheme] precedence branch.
 */
expect fun supportsDynamicColor(): Boolean

/**
 * Applies the LibreChat Material 3 theme.
 *
 * Color resolution precedence:
 * 1. [useDynamicColor] on **and** the platform supports it -> wallpaper-based scheme.
 * 2. Otherwise the full scheme is generated from [accentColor] via MaterialKolor
 *    ([rememberDynamicColorScheme], remembered/keyed on its inputs).
 *
 * [PaletteStyle.TonalSpot] keeps only the seed's hue — every role is re-derived at a fixed
 * chroma and a fixed per-role tone, so no role in the generated scheme resolves to the seed hex.
 * The hue feeds the neutral palette as well as the accent one, which is why it tints surfaces
 * app-wide.
 */
@Composable
fun LibreChatTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    accentColor: Color = DefaultAccentSeed,
    useDynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val seedScheme = rememberDynamicColorScheme(accentColor, darkTheme, style = PaletteStyle.TonalSpot)
    val colorScheme = if (useDynamicColor && supportsDynamicColor()) {
        platformColorScheme(darkTheme, dynamicColor = true) ?: seedScheme
    } else {
        seedScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = libreChatTypography,
        shapes = libreChatShapes,
        content = content,
    )
}
