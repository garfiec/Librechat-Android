package com.garfiec.librechat.core.common.extensions

import kotlin.math.roundToInt

/**
 * Formats a byte count as a human-readable size string, scaling to the largest unit under 1024
 * and showing one decimal place with a trailing ".0" trimmed — e.g. `512 B`, `1.2 MB`, `20 MB`,
 * `3.4 GB`. Pure Kotlin so it works in `commonMain` on both platforms.
 *
 * Note: `feature/files` keeps its own `formatFileSize` (an `expect/actual` that uses iOS's native
 * `NSByteCountFormatter`), and `feature/settings` intentionally renders integer KB with no GB tier.
 * Those deliberately diverge; this helper is the default for new call sites.
 */
fun formatByteSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    var value = bytes / 1024.0
    var unitIndex = 0
    // Advance while the value would round (at one decimal) to 1024 or more, not just when it is
    // literally >= 1024 — otherwise a value like 1023.99 stays in this tier and prints "1024 KB"
    // instead of rolling up to "1 MB".
    while (value >= 1023.95 && unitIndex < UNITS.lastIndex) {
        value /= 1024.0
        unitIndex++
    }
    return "${trimOneDecimal(value)} ${UNITS[unitIndex]}"
}

private val UNITS = listOf("KB", "MB", "GB", "TB")

/** Rounds [value] to one decimal and drops a trailing ".0" (2.0 -> "2", 1.25 -> "1.3"). */
private fun trimOneDecimal(value: Double): String {
    val tenths = (value * 10).roundToInt()
    val whole = tenths / 10
    val fraction = tenths % 10
    return if (fraction == 0) "$whole" else "$whole.$fraction"
}
