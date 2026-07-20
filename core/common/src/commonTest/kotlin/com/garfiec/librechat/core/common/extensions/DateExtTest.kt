package com.garfiec.librechat.core.common.extensions

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class DateExtTest {

    // Fixed reference so the assertions don't drift with the wall clock. UTC keeps the day
    // arithmetic deterministic regardless of where the test runs.
    private val now = Instant.parse("2026-07-19T12:00:00Z")
    private val reference = RelativeTimeReference(now, TimeZone.UTC)

    @Test
    fun relativeTimeCoversEachBoundary() {
        assertEquals("Just now", (now - 30.seconds).toRelativeTimeString(reference))
        assertEquals("1m ago", (now - 1.minutes).toRelativeTimeString(reference))
        assertEquals("59m ago", (now - 59.minutes).toRelativeTimeString(reference))
        assertEquals("1h ago", (now - 1.hours).toRelativeTimeString(reference))
        assertEquals("23h ago", (now - 23.hours).toRelativeTimeString(reference))
        assertEquals("1d ago", (now - 1.days).toRelativeTimeString(reference))
        assertEquals("6d ago", (now - 6.days).toRelativeTimeString(reference))
        // 7 days is the first instant that falls through to an absolute month-day label. The month
        // name is locale-dependent (formatMonthAbbrev delegates to the platform), so build the
        // expectation the same way rather than hardcoding English — the branch is what's under test.
        assertEquals("${formatMonthAbbrev(7)} 12", (now - 7.days).toRelativeTimeString(reference))
    }

    /**
     * The whole point of taking a [RelativeTimeReference] rather than reading the clock internally:
     * a caller that advances the reference gets an advanced label. This is what lets a row's
     * relative time refresh — see `LocalRelativeTimeReference` in feature/conversations, which ticks
     * the reference so the label doesn't freeze at whatever it said when the row was composed.
     */
    @Test
    fun labelAdvancesWhenTheReferenceAdvances() {
        val stamp = now - 30.seconds

        assertEquals("Just now", stamp.toRelativeTimeString(reference))
        assertEquals(
            "5m ago",
            stamp.toRelativeTimeString(RelativeTimeReference(now + 5.minutes, TimeZone.UTC)),
        )
        assertEquals(
            "2h ago",
            stamp.toRelativeTimeString(RelativeTimeReference(now + 2.hours, TimeZone.UTC)),
        )
    }

    @Test
    fun relativeDateGroupCoversEachBoundary() {
        assertEquals("Today", now.toRelativeDateGroup(reference))
        assertEquals("Yesterday", (now - 1.days).toRelativeDateGroup(reference))
        // Assert the edges of each bucket, not their interiors: a `<=` slipping to `<` has to fail.
        assertEquals("Previous 7 Days", (now - 2.days).toRelativeDateGroup(reference))
        assertEquals("Previous 7 Days", (now - 7.days).toRelativeDateGroup(reference))
        assertEquals("Previous 30 Days", (now - 8.days).toRelativeDateGroup(reference))
        assertEquals("Previous 30 Days", (now - 30.days).toRelativeDateGroup(reference))

        // Older than 30 days switches to a month-year label. Locale-dependent again, so derive the
        // expectation from the same platform formatter.
        val old = now - 31.days
        val oldDate = old.toLocalDateTime(TimeZone.UTC).date
        assertEquals(
            formatMonthYear(oldDate.monthNumber, oldDate.year),
            old.toRelativeDateGroup(reference),
        )
    }

}
