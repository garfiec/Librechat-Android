package com.garfiec.librechat.core.common.extensions

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * "Now", as the relative formatters below see it.
 *
 * Both formatters resolve the clock and the system timezone on every invocation, which for a list
 * mapping means that loop-invariant work runs once per row. Hoisting it into a value lets a caller
 * mapping N conversations resolve it once. Every formatter defaults to [current], so one-off call
 * sites (a single row in a composable) read exactly as they did before; only list paths pass a
 * shared instance.
 */
data class RelativeTimeReference(
    val now: Instant,
    val timeZone: TimeZone,
) {
    /**
     * Eager, and cheap to keep that way: references are hoisted, so this is computed roughly once
     * per list emission and once per clock tick — never per row. (An earlier revision made this
     * `by lazy` back when every row built its own reference from the default argument; that is no
     * longer how any production call site works, and `by lazy` would now add a volatile read to
     * [toRelativeDateGroup]'s per-row path to save a handful of conversions a minute.)
     */
    val today: LocalDate = now.toLocalDateTime(timeZone).date

    companion object {
        fun current(): RelativeTimeReference =
            RelativeTimeReference(Clock.System.now(), TimeZone.currentSystemDefault())
    }
}

fun Instant.toRelativeDateGroup(
    reference: RelativeTimeReference = RelativeTimeReference.current(),
): String {
    val date = toLocalDateTime(reference.timeZone).date
    val daysBetween = date.daysUntil(reference.today)
    return when {
        daysBetween == 0 -> "Today"
        daysBetween == 1 -> "Yesterday"
        daysBetween <= 7 -> "Previous 7 Days"
        daysBetween <= 30 -> "Previous 30 Days"
        else -> date.formatMonthYear()
    }
}

/**
 * Short "time since" label for list rows: "Just now", "5m ago", "3h ago", "2d ago", then "Mar 14".
 *
 * The result depends on the wall clock, so **where you call this decides whether it ever updates**.
 * Computing it during a ViewModel mapping freezes it into immutable state until unrelated data
 * changes; computing it inside `remember(row.updatedAt)` freezes it just as hard, because `remember`
 * exists precisely not to recompute across recompositions and `updatedAt` does not change as time
 * passes. Either way the label rots.
 *
 * To get a label that actually advances, the caller must supply a [reference] that changes — see
 * `ProvideRelativeTimeReference` / `LocalRelativeTimeReference` in feature/conversations, which
 * tick one and thereby invalidate exactly the composables reading it.
 */
fun Instant.toRelativeTimeString(
    reference: RelativeTimeReference = RelativeTimeReference.current(),
): String {
    val duration = reference.now - this
    val minutes = duration.inWholeMinutes
    val hours = duration.inWholeHours
    val days = duration.inWholeDays

    return when {
        minutes < 1 -> "Just now"
        minutes < 60 -> "${minutes}m ago"
        hours < 24 -> "${hours}h ago"
        days < 7 -> "${days}d ago"
        else -> {
            val date = toLocalDateTime(reference.timeZone).date
            "${formatMonthAbbrev(date.monthNumber)} ${date.dayOfMonth}"
        }
    }
}

private fun LocalDate.formatMonthYear(): String =
    formatMonthYear(monthNumber, year)
