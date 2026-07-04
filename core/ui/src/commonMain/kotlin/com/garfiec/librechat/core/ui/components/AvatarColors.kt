package com.garfiec.librechat.core.ui.components

import androidx.compose.ui.graphics.Color

/**
 * Curated background colors for letter-fallback avatars (accounts/users without a photo). Chosen
 * for strong mutual contrast so different accounts are easy to tell apart at a glance, and all dark
 * enough that white text stays legible on any of them. Deliberately fixed literals rather than
 * theme colors so a given account keeps the same color across light/dark themes, devices, and app
 * restarts.
 */
val AvatarPalette: List<Color> = listOf(
    Color(0xFFD32F2F), // red
    Color(0xFFC2185B), // pink
    Color(0xFF7B1FA2), // purple
    Color(0xFF512DA8), // deep purple
    Color(0xFF303F9F), // indigo
    Color(0xFF1976D2), // blue
    Color(0xFF0288D1), // light blue
    Color(0xFF00796B), // teal
    Color(0xFF388E3C), // green
    Color(0xFF689F38), // olive green
    Color(0xFFF57C00), // orange
    Color(0xFFE64A19), // deep orange
    Color(0xFF5D4037), // brown
    Color(0xFF455A64), // blue grey
)

/**
 * Deterministically maps [seed] to a stable [AvatarPalette] entry — the same seed always yields the
 * same color. Uses a wrapping 31-based hash computed explicitly (rather than [String.hashCode]) so
 * the result is identical across JVM and Native. Seed with a stable per-account key (e.g. the
 * account id), not a mutable display label.
 */
fun avatarColorForSeed(seed: String): Color {
    var hash = 0
    for (ch in seed) {
        hash = hash * 31 + ch.code
    }
    val index = ((hash % AvatarPalette.size) + AvatarPalette.size) % AvatarPalette.size
    return AvatarPalette[index]
}
