package com.garfiec.librechat.feature.settings.screen

import androidx.compose.runtime.Composable
import com.garfiec.librechat.feature.settings.resources.Res
import com.garfiec.librechat.feature.settings.resources.prefetch_condition_enabled
import com.garfiec.librechat.feature.settings.resources.prefetch_condition_enabled_unmet
import com.garfiec.librechat.feature.settings.resources.prefetch_condition_foreground
import com.garfiec.librechat.feature.settings.resources.prefetch_condition_idle
import com.garfiec.librechat.feature.settings.resources.prefetch_condition_idle_met
import com.garfiec.librechat.feature.settings.resources.prefetch_condition_idle_unmet
import com.garfiec.librechat.feature.settings.resources.prefetch_condition_network
import com.garfiec.librechat.feature.settings.resources.prefetch_condition_network_met
import com.garfiec.librechat.feature.settings.resources.prefetch_condition_network_offline
import com.garfiec.librechat.feature.settings.resources.prefetch_condition_network_unmet
import com.garfiec.librechat.feature.settings.resources.prefetch_condition_power
import com.garfiec.librechat.feature.settings.resources.prefetch_condition_power_met
import com.garfiec.librechat.feature.settings.resources.prefetch_condition_power_unmet
import com.garfiec.librechat.feature.settings.resources.prefetch_condition_server
import com.garfiec.librechat.feature.settings.resources.prefetch_condition_server_met
import com.garfiec.librechat.feature.settings.resources.prefetch_condition_server_unmet
import com.garfiec.librechat.feature.settings.resources.prefetch_status_off
import com.garfiec.librechat.feature.settings.resources.prefetch_status_paused_background
import com.garfiec.librechat.feature.settings.resources.prefetch_status_paused_busy
import com.garfiec.librechat.feature.settings.resources.prefetch_status_paused_network
import com.garfiec.librechat.feature.settings.resources.prefetch_status_paused_offline
import com.garfiec.librechat.feature.settings.resources.prefetch_status_paused_power
import com.garfiec.librechat.feature.settings.resources.prefetch_status_rate_limited
import com.garfiec.librechat.feature.settings.resources.prefetch_status_stopped
import com.garfiec.librechat.feature.settings.resources.prefetch_status_up_to_date
import com.garfiec.librechat.feature.settings.resources.prefetch_status_waiting
import com.garfiec.librechat.feature.settings.resources.prefetch_status_warming
import com.garfiec.librechat.feature.settings.resources.prefetch_status_working
import com.garfiec.librechat.feature.settings.state.PrefetchDisplayStatus
import com.garfiec.librechat.feature.settings.state.PrefetchPauseReason
import com.garfiec.librechat.feature.settings.viewmodel.PrefetchCondition
import com.garfiec.librechat.feature.settings.viewmodel.PrefetchConditionRow
import org.jetbrains.compose.resources.stringResource

/**
 * String mappings for the prefetch readout, shared by the settings summary and the activity screen
 * so the two cannot describe the same state differently.
 */
@Composable
fun PrefetchDisplayStatus.label(): String = when (this) {
    PrefetchDisplayStatus.Off -> stringResource(Res.string.prefetch_status_off)
    PrefetchDisplayStatus.Working -> stringResource(Res.string.prefetch_status_working)
    is PrefetchDisplayStatus.Warming ->
        stringResource(Res.string.prefetch_status_warming, completed + 1, total)
    PrefetchDisplayStatus.RateLimited -> stringResource(Res.string.prefetch_status_rate_limited)
    PrefetchDisplayStatus.Stopped -> stringResource(Res.string.prefetch_status_stopped)
    PrefetchDisplayStatus.UpToDate -> stringResource(Res.string.prefetch_status_up_to_date)
    is PrefetchDisplayStatus.Waiting -> stringResource(Res.string.prefetch_status_waiting, pending)
    is PrefetchDisplayStatus.Paused -> when (reason) {
        PrefetchPauseReason.OFFLINE -> stringResource(Res.string.prefetch_status_paused_offline)
        PrefetchPauseReason.NETWORK -> stringResource(Res.string.prefetch_status_paused_network)
        PrefetchPauseReason.POWER -> stringResource(Res.string.prefetch_status_paused_power)
        PrefetchPauseReason.BUSY -> stringResource(Res.string.prefetch_status_paused_busy)
        PrefetchPauseReason.BACKGROUND -> stringResource(Res.string.prefetch_status_paused_background)
    }
}

@Composable
fun PrefetchCondition.label(): String = stringResource(
    when (this) {
        PrefetchCondition.ENABLED -> Res.string.prefetch_condition_enabled
        PrefetchCondition.FOREGROUND -> Res.string.prefetch_condition_foreground
        PrefetchCondition.NETWORK -> Res.string.prefetch_condition_network
        PrefetchCondition.POWER -> Res.string.prefetch_condition_power
        PrefetchCondition.IDLE -> Res.string.prefetch_condition_idle
        PrefetchCondition.SERVER -> Res.string.prefetch_condition_server
    },
)

/**
 * The detail line beside a condition. Foreground has none: it is the one condition that is
 * necessarily met while the screen showing it is on top, so any wording would be filler.
 */
@Composable
fun PrefetchConditionRow.detail(): String? = when (condition) {
    PrefetchCondition.FOREGROUND -> null
    PrefetchCondition.ENABLED ->
        if (met) null else stringResource(Res.string.prefetch_condition_enabled_unmet)
    // Offline is reported even when the row is otherwise met: with the metered override on, the
    // network condition passes on a device with no connection at all, and saying nothing there
    // would leave "Warm now" enabled with no hint why a pass will fail.
    PrefetchCondition.NETWORK -> when {
        offline -> stringResource(Res.string.prefetch_condition_network_offline)
        met -> stringResource(Res.string.prefetch_condition_network_met)
        else -> stringResource(Res.string.prefetch_condition_network_unmet)
    }
    PrefetchCondition.POWER -> stringResource(
        if (met) Res.string.prefetch_condition_power_met else Res.string.prefetch_condition_power_unmet,
    )
    PrefetchCondition.IDLE -> stringResource(
        if (met) Res.string.prefetch_condition_idle_met else Res.string.prefetch_condition_idle_unmet,
    )
    PrefetchCondition.SERVER -> stringResource(
        if (met) Res.string.prefetch_condition_server_met else Res.string.prefetch_condition_server_unmet,
    )
}
