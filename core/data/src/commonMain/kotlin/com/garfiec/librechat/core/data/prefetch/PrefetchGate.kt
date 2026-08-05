package com.garfiec.librechat.core.data.prefetch

import com.garfiec.librechat.core.common.lifecycle.ForegroundSignal
import com.garfiec.librechat.core.common.network.NetworkConditionObserver
import com.garfiec.librechat.core.common.network.RequestActivityTracker
import com.garfiec.librechat.core.common.power.PowerStateObserver
import com.garfiec.librechat.core.data.datastore.SettingsDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * The single answer to "may background prefetching run right now".
 *
 * Everything that should stop the prefetcher is folded into one boolean, so the engine has no
 * conditions of its own to check and `PrefetchController` needs no per-condition teardown.
 */
class PrefetchGate(
    private val foregroundSignal: ForegroundSignal,
    private val settingsDataStore: SettingsDataStore,
    private val networkConditionObserver: NetworkConditionObserver,
    private val powerStateObserver: PowerStateObserver,
    private val requestActivityTracker: RequestActivityTracker,
) {

    /**
     * Nested rather than flat because Kotlin's [combine] takes at most five flows and there are six
     * inputs. The network pair nests naturally: the override only ever modifies the metering answer.
     */
    private val networkAllowed: Flow<Boolean> = combine(
        networkConditionObserver.isUnmetered,
        settingsDataStore.prefetchOnMeteredEnabled,
    ) { unmetered, allowMetered -> unmetered || allowMetered }

    fun isOpen(): Flow<Boolean> = combine(
        foregroundSignal.isForeground,
        settingsDataStore.prefetchEnabled,
        networkAllowed,
        powerStateObserver.isPowerConstrained.map { !it },
        // No debounce and no quiet-period timer: the rule is "not while a request is in flight", not
        // "not near one". Adding a settle delay here would also delay resuming after every tap.
        requestActivityTracker.userInFlight.map { it == 0 },
    ) { foreground, enabled, network, powerOk, idle ->
        foreground && enabled && network && powerOk && idle
    }.distinctUntilChanged()
}
