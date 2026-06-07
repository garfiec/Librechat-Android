package com.garfiec.librechat.core.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The default accent seed color (the app's original lavender brand hue, `0xFF8B5CF6`).
 * Used to generate the Material 3 scheme when the user has not chosen a custom accent
 * and wallpaper-based dynamic color is off.
 */
val DefaultAccentSeed = Color(0xFF8B5CF6)

/**
 * Curated set of accent seed colors offered in the picker. The full Material 3
 * [androidx.compose.material3.ColorScheme] is generated from whichever seed the
 * user selects, so these are source colors, not final role colors. Lavender
 * (the default) is listed first.
 */
val AccentColorPresets: List<Color> = listOf(
    DefaultAccentSeed, // Lavender (default)
    Color(0xFF3B82F6), // Blue
    Color(0xFF6366F1), // Indigo
    Color(0xFF06B6D4), // Cyan
    Color(0xFF14B8A6), // Teal
    Color(0xFF22C55E), // Green
    Color(0xFFF59E0B), // Amber
    Color(0xFFF97316), // Orange
    Color(0xFFEF4444), // Red
    Color(0xFFEC4899), // Pink
    Color(0xFF64748B), // Slate
)
