package com.garfiec.librechat.feature.settings.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import com.garfiec.librechat.core.common.extensions.RelativeTimeReference
import com.garfiec.librechat.core.common.extensions.toRelativeTimeString
import kotlinx.coroutines.delay
import kotlin.time.Instant

/**
 * A [RelativeTimeReference] that advances while the screen is open.
 *
 * "Warmed 2m ago" is computed from the wall clock, so a reference captured once freezes the label
 * until unrelated state changes — and this screen exists to answer "is anything happening", where a
 * timestamp that has silently stopped moving is precisely the wrong answer. Re-resolving on a timer
 * invalidates only the composables that read it.
 */
@Composable
fun rememberTickingTimeReference(): RelativeTimeReference {
    val reference by produceState(RelativeTimeReference.current()) {
        while (true) {
            delay(TICK_MILLIS)
            value = RelativeTimeReference.current()
        }
    }
    return reference
}

/** Epoch millis as a "5m ago" label, matching how the conversation list renders timestamps. */
fun Long.relativeLabel(reference: RelativeTimeReference): String =
    Instant.fromEpochMilliseconds(this).toRelativeTimeString(reference)

/** Fine enough for a minute-resolution label without waking the screen more than it needs. */
private const val TICK_MILLIS = 30_000L
