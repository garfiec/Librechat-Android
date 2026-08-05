package com.garfiec.librechat.core.common.di

import com.garfiec.librechat.core.common.AndroidAppInfo
import com.garfiec.librechat.core.common.AppInfo
import com.garfiec.librechat.core.common.network.AndroidConnectivityObserver
import com.garfiec.librechat.core.common.network.AndroidNetworkConditionObserver
import com.garfiec.librechat.core.common.network.ConnectivityObserver
import com.garfiec.librechat.core.common.network.NetworkConditionObserver
import com.garfiec.librechat.core.common.power.AndroidPowerStateObserver
import com.garfiec.librechat.core.common.power.PowerStateObserver
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

actual val commonPlatformModule: Module = module {
    single<ConnectivityObserver> { AndroidConnectivityObserver(androidContext()) }
    single<NetworkConditionObserver> { AndroidNetworkConditionObserver(androidContext()) }
    single<PowerStateObserver> { AndroidPowerStateObserver(androidContext()) }
    single<AppInfo> { AndroidAppInfo(androidContext()) }
}
