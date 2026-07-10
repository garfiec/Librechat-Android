package com.garfiec.librechat.shared

import com.garfiec.librechat.shared.di.sharedKoinModules
import org.koin.dsl.module

/**
 * iOS Koin graph. Reuses the shared module list (which includes `networkModule`,
 * so the Darwin engine + iOS `SseHttpTransport` come from `networkPlatformModule.ios`)
 * and adds only the one iOS-only binding no shared module provides: `LibreChatSDK`,
 * which Swift resolves at launch via `IosKoinAccessor.getSDK()`.
 *
 * Everything the old inline graph re-implemented (Json, SwitchGate, the HTTP clients,
 * the API services, `SseClient`) now lives in `networkModule` and is shared with
 * Android verbatim — see `SharedKoinModules`.
 */
val iosSharedModule = module {
    includes(sharedKoinModules)

    single {
        LibreChatSDK(
            authApi = get(),
            chatApi = get(),
            sseClient = get(),
            tokenManager = get(),
        )
    }
}
