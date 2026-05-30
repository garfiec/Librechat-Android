package com.garfiec.librechat.core.logging

/**
 * Tunables for the persistent diagnostic log buffer. Provided via Koin so build types can override.
 *
 * The buffer is two-segment: [totalMaxBytes] is split across the active and previous segments, so
 * the on-disk footprint stays between `totalMaxBytes/2` and `totalMaxBytes`.
 */
data class LogConfig(
    val totalMaxBytes: Long = DEFAULT_TOTAL_MAX_BYTES,
    val maxAgeMillis: Long = DEFAULT_MAX_AGE_MILLIS,
    val channelCapacity: Int = DEFAULT_CHANNEL_CAPACITY,
    // Cap a captured throwable's rendered length so a few exceptions can't dominate the buffer.
    // Crash records use a larger cap (they're rare and the full trace matters most).
    val maxThrowableChars: Int = DEFAULT_MAX_THROWABLE_CHARS,
    val maxCrashThrowableChars: Int = DEFAULT_MAX_CRASH_THROWABLE_CHARS,
) {
    val segmentCapBytes: Long get() = totalMaxBytes / 2

    companion object {
        const val DEFAULT_TOTAL_MAX_BYTES: Long = 4L * 1024 * 1024 // 4 MiB total → 2 MiB/segment
        const val DEFAULT_MAX_AGE_MILLIS: Long = 7L * 24 * 60 * 60 * 1000 // 7 days
        const val DEFAULT_CHANNEL_CAPACITY: Int = 1024
        const val DEFAULT_MAX_THROWABLE_CHARS: Int = 1500
        const val DEFAULT_MAX_CRASH_THROWABLE_CHARS: Int = 6000
    }
}
