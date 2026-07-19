package com.garfiec.librechat.core.common.extensions

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Emits a [RelativeTimeReference] immediately, then again at each local midnight.
 *
 * Date *grouping* is the other half of the staleness problem that `LocalRelativeTimeReference`
 * solves for row subtitles. "Today" / "Yesterday" / "Previous 7 Days" are not just labels — they
 * decide which section a conversation belongs to — so unlike a subtitle they cannot be fixed by
 * reformatting at render time; the grouping itself has to re-run. Combining this flow into a
 * grouping pipeline gives it a clock input, so a list left open across midnight re-buckets instead
 * of showing yesterday's sections until an unrelated Room emission happens to refresh it.
 *
 * One emission per day, so the cost is nil. The wait is a plain [delay], which on Android does not
 * advance while the device is in deep sleep — a boundary crossed while asleep therefore lands late,
 * on wake, rather than exactly at midnight. Each iteration re-reads the clock, so a late emission is
 * still *correct*, just not punctual.
 */
fun dayBoundaryReferences(): Flow<RelativeTimeReference> = flow {
    while (true) {
        val reference = RelativeTimeReference.current()
        emit(reference)
        delay(reference.untilNextMidnight())
    }
}

/**
 * How long until the next local midnight, per this reference's own clock and zone.
 *
 * Split out from [dayBoundaryReferences] because it is the only part with logic worth testing: the
 * flow itself reads the real clock while `runTest` advances a virtual one, so a test of the loop
 * would observe two emissions at the same wall-clock instant and prove nothing.
 */
internal fun RelativeTimeReference.untilNextMidnight(): Duration {
    val nextMidnight = today
        .plus(1, DateTimeUnit.DAY)
        .atStartOfDayIn(timeZone)
    // Never zero: a non-positive wait (clock moved backwards, a DST jump) would spin the loop.
    return (nextMidnight - now).coerceAtLeast(1.seconds)
}
