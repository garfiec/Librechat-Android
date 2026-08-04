package com.garfiec.librechat.core.ui.theme

import androidx.compose.ui.graphics.Color
import com.garfiec.librechat.core.model.DEFAULT_ACCENT_SEED_ARGB

/**
 * The default accent seed color (canonical value in [DEFAULT_ACCENT_SEED_ARGB]). Used to
 * generate the Material 3 scheme when the user has not chosen a custom accent and
 * wallpaper-based dynamic color is off.
 */
val DefaultAccentSeed = Color(DEFAULT_ACCENT_SEED_ARGB)

/**
 * Curated set of accent seed colors offered in the picker. The full Material 3
 * [androidx.compose.material3.ColorScheme] is generated from whichever seed the
 * user selects, so these are source colors, not final role colors — the swatch a
 * user taps is not the color the app will paint.
 *
 * The default is listed first and must not be repeated further down: the picker marks
 * selection by comparing each preset's ARGB against the working value, so a duplicate
 * renders as two simultaneously selected swatches.
 */
val AccentColorPresets: List<Color> = listOf(
    DefaultAccentSeed, // Blue (default)
    Color(0xFF6366F1), // Indigo
    Color(0xFF8B5CF6), // Lavender (the original brand hue)
    Color(0xFF06B6D4), // Cyan
    Color(0xFF00D8BB), // Turquoise (matches the app icon's cable)
    Color(0xFF22C55E), // Green
    Color(0xFFF59E0B), // Amber
    Color(0xFFF97316), // Orange
    Color(0xFFEF4444), // Red
    Color(0xFFEC4899), // Pink
    Color(0xFF64748B), // Slate
)
