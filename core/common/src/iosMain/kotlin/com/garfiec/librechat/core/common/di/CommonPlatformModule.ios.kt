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
    // UNSUPPORTED even though iOS runs background passes: latching the window here puts no bound on
    // when a pass may start, so one could begin minutes into backgrounding off a gate that reopened
    // on its own and be suspended mid-request with nothing holding the process up. Every off-screen
    // pass instead runs inside an explicit background run paired with something keeping the process
    // alive — the scheduler's BGTask, or the assertion in PrefetchBackgroundTasks.swift.
    single { BackgroundWorkSupport.UNSUPPORTED }
    single { DeferredWorkWindow(foregroundSignal = get(), support = get()) }
}
