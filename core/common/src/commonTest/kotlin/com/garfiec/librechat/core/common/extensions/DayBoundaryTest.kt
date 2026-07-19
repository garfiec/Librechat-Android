package com.garfiec.librechat.core.common.extensions

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

class DayBoundaryTest {

    private fun referenceAt(iso: String, zone: TimeZone = TimeZone.UTC) =
        RelativeTimeReference(Instant.parse(iso), zone)

    /**
     * Grouping `combine`s on this flow, and `combine` emits nothing until every source has emitted —
     * so an implementation that waited for the first midnight before its first emission would leave
     * the conversation list permanently *empty* rather than merely stale.
     */
    @Test
    fun emitsImmediatelyWithoutWaitingForMidnight() = runTest {
        val emissions = dayBoundaryReferences().take(1).toList()

        assertEquals(1, emissions.size)
    }

    @Test
    fun waitsUntilTheNextLocalMidnight() {
        assertEquals(12.hours, referenceAt("2026-07-19T12:00:00Z").untilNextMidnight())
        assertEquals(1.hours, referenceAt("2026-07-19T23:00:00Z").untilNextMidnight())
        // Exactly at midnight, the *next* boundary is a full day out — not zero.
        assertEquals(24.hours, referenceAt("2026-07-19T00:00:00Z").untilNextMidnight())
    }

    /** Midnight is local, so the same instant yields a different wait in a different zone. */
    @Test
    fun midnightIsResolvedInTheReferenceZone() {
        val instant = "2026-07-19T12:00:00Z"

        assertEquals(12.hours, referenceAt(instant, TimeZone.UTC).untilNextMidnight())
        // 12:00Z is 08:00 EDT; the next New York midnight is 04:00Z the following day.
        assertEquals(
            16.hours,
            referenceAt(instant, TimeZone.of("America/New_York")).untilNextMidnight(),
        )
    }

    /**
     * Whatever the zone or time of day, the wait is always positive — `delay(0)` in the flow's loop
     * would spin, pegging a core and re-grouping the list continuously. (`today` is derived from
     * `now`, so a reference cannot be constructed in a state where this is violated; the
     * `coerceAtLeast` in the implementation is belt-and-braces against a future refactor that
     * decouples them.)
     */
    @Test
    fun waitIsAlwaysPositive() {
        val zones = listOf(TimeZone.UTC, TimeZone.of("America/New_York"), TimeZone.of("Asia/Kolkata"))
        val instants = listOf(
            "2026-07-19T00:00:00Z",
            "2026-07-19T12:00:00Z",
            "2026-07-19T23:59:59Z",
            // Spans a US DST transition.
            "2026-11-01T05:30:00Z",
        )

        for (zone in zones) {
            for (instant in instants) {
                val wait = referenceAt(instant, zone).untilNextMidnight()
                assertTrue(wait > Duration.ZERO, "non-positive wait for $instant in $zone: $wait")
            }
        }
    }
}
