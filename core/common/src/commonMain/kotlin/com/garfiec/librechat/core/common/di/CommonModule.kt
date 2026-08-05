package com.garfiec.librechat.core.common.di

import com.garfiec.librechat.core.common.conversation.OpenConversationRegistry
import com.garfiec.librechat.core.common.lifecycle.ForegroundSignal
import com.garfiec.librechat.core.common.network.RequestActivityTracker
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.module.Module
import org.koin.dsl.module

expect val commonPlatformModule: Module

val commonModule = module {
    includes(commonPlatformModule)

    // Dispatchers
    single<CoroutineDispatcher>(KoinQualifiers.IO) { ioDispatcher }
    single<CoroutineDispatcher>(KoinQualifiers.Default) { Dispatchers.Default }
    single<CoroutineDispatcher>(KoinQualifiers.Main) { Dispatchers.Main }

    // Note: This scope lives for the entire app process lifetime.
    // Coroutines launched here persist across logout/login cycles.
    // If session-scoped work is needed, use a dedicated session scope instead.
    single<CoroutineScope>(KoinQualifiers.ApplicationScope) {
        CoroutineScope(SupervisorJob() + get<CoroutineDispatcher>(KoinQualifiers.Default))
    }

    // Activity signals. They belong at the bottom of the module graph: the HTTP layer publishes to
    // them and the data layer reads them, and neither may depend on the other.
    single { RequestActivityTracker() }
    single { ForegroundSignal() }
    single { OpenConversationRegistry() }
}
