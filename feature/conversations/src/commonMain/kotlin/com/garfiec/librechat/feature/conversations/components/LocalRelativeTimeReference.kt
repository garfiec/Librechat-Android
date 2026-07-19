package com.garfiec.librechat.feature.conversations.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.garfiec.librechat.core.common.extensions.RelativeTimeReference
import kotlinx.coroutines.delay
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * The "now" that conversation rows format their relative-time labels against.
 *
 * Deliberately a *changing* value. A relative label ("5m ago") is only correct for about a minute,
 * and neither of the obvious places to compute it ever refreshes: a ViewModel mapping re-runs only
 * on a data change, and `remember(row.updatedAt)` re-runs only when the key changes — which it
 * never does, since a row's timestamp is fixed while the clock is what moves. Reading this local
 * inside a row gives Compose a reason to recompose it: when [ProvideRelativeTimeReference] ticks,
 * every composable that read the local is invalidated, and nothing else is.
 *
 * Non-static on purpose. A `staticCompositionLocalOf` would recompose the whole provided subtree on
 * each tick instead of just the readers, which for a drawer full of rows is the opposite of what we
 * want.
 *
 * The default is a fixed reference captured at first read, i.e. a label that is correct when it
 * appears and then stops advancing. That is a deliberately harmless fallback for a surface that
 * forgets the provider — same behaviour as before this existed, not a crash — but it does mean
 * **the provider is required for labels to actually tick**.
 */
val LocalRelativeTimeReference = compositionLocalOf { RelativeTimeReference.current() }

/**
 * Ticks [LocalRelativeTimeReference] for [content].
 *
 * One coroutine per list surface, not one per row. [updateInterval] is the label's worst-case
 * staleness; a minute matches the finest bucket the formatter produces ("1m ago"), so a shorter
 * interval would only buy recompositions that render identical text.
 */
@Composable
fun ProvideRelativeTimeReference(
    updateInterval: Duration = 1.minutes,
    content: @Composable () -> Unit,
) {
    var reference by remember { mutableStateOf(RelativeTimeReference.current()) }

    LaunchedEffect(updateInterval) {
        while (true) {
            delay(updateInterval)
            reference = RelativeTimeReference.current()
        }
    }

    CompositionLocalProvider(
        LocalRelativeTimeReference provides reference,
        content = content,
    )
}
