package com.garfiec.librechat.feature.conversations.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
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
 * **There is no safe default, so there isn't one.** The obvious fallback —
 * `compositionLocalOf { RelativeTimeReference.current() }` — is a trap: Compose memoizes a local's
 * default in a `LazyValueHolder` for the lifetime of the process, so the *first* provider-less read
 * anywhere freezes one `now` that every later provider-less read reuses. A row composed eight hours
 * later would date itself against app-launch time and could render a future timestamp as
 * "Just now". Failing loudly beats shipping that.
 */
val LocalRelativeTimeReference = compositionLocalOf<RelativeTimeReference> {
    error(
        "No RelativeTimeReference provided. Wrap the surface in ProvideRelativeTimeReference — " +
            "without it, relative-time labels would silently freeze.",
    )
}

/**
 * Ticks [LocalRelativeTimeReference] for [content].
 *
 * One coroutine per list surface, not one per row. [updateInterval] is the label's worst-case
 * staleness; a minute matches the finest bucket the formatter produces ("1m ago"), so a shorter
 * interval would only buy recompositions that render identical text.
 *
 * Gated on RESUMED, which does two jobs. It stops the tick while the app is backgrounded — the
 * drawer is composed for the whole session (a closed `ModalNavigationDrawer` is offset off-screen,
 * not removed from the tree), so an ungated loop would recompose rows nobody can see, forever. And
 * because leaving RESUMED restarts the effect, coming back re-reads the clock immediately: `delay`
 * runs on a monotonic clock that does not advance during device sleep, so a phone asleep for eight
 * hours would otherwise resume still showing "2m ago" until the pending delay elapsed.
 */
@Composable
fun ProvideRelativeTimeReference(
    updateInterval: Duration = 1.minutes,
    content: @Composable () -> Unit,
) {
    var reference by remember { mutableStateOf(RelativeTimeReference.current()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateAsState()

    LaunchedEffect(updateInterval, lifecycleState) {
        if (!lifecycleState.isAtLeast(Lifecycle.State.RESUMED)) return@LaunchedEffect
        while (true) {
            reference = RelativeTimeReference.current()
            delay(updateInterval)
        }
    }

    CompositionLocalProvider(
        LocalRelativeTimeReference provides reference,
        content = content,
    )
}
