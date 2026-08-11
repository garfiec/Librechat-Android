package com.garfiec.librechat.core.common.di

import com.garfiec.librechat.core.common.AppInfo
import com.garfiec.librechat.core.common.IosAppInfo
import com.garfiec.librechat.core.common.lifecycle.BackgroundWorkSupport
import com.garfiec.librechat.core.common.lifecycle.DeferredWorkWindow
import com.garfiec.librechat.core.common.network.ConnectivityObserver
import com.garfiec.librechat.core.common.network.IosConnectivityObserver
import com.garfiec.librechat.core.common.network.IosNetworkConditionObserver
import com.garfiec.librechat.core.common.network.NetworkConditionObserver
import com.garfiec.librechat.core.common.power.IosPowerStateObserver
import com.garfiec.librechat.core.common.power.PowerStateObserver
import org.koin.core.module.Module
import org.koin.dsl.module

actual val commonPlatformModule: Module = module {
    single<ConnectivityObserver> { IosConnectivityObserver() }
    single<NetworkConditionObserver> { IosNetworkConditionObserver() }
    single<PowerStateObserver> { IosPowerStateObserver() }
    single<AppInfo> { IosAppInfo() }
    // Deliberately still UNSUPPORTED even though iOS now runs background passes, because what this
    // flag really selects is whether the window may open with *nothing holding the process up*.
    // Latching it here would leave no bound on when a pass could start: the app could be minutes into
    // being backgrounded, begin a pass off the back of a gate that reopened on its own, and be
    // suspended mid-request with no assertion ever taken — the single sample on the backgrounding
    // edge cannot cover a pass that had not started yet.
    //
    // So on iOS every off-screen pass runs inside an explicit background run instead, and both
    // openers pair one with something keeping the process alive: the scheduler's runner with its
    // BGTask, and PrefetchBackgroundTasks.swift with a UIApplication assertion. Releasing either
    // closes the window, which is what cancels the pass rather than leaving it to be frozen.
    single { BackgroundWorkSupport.UNSUPPORTED }
    single { DeferredWorkWindow(foregroundSignal = get(), support = get()) }
}
