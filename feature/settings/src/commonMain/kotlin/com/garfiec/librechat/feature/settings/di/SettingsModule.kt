package com.garfiec.librechat.feature.settings.di

import com.garfiec.librechat.core.common.di.KoinQualifiers
import com.garfiec.librechat.feature.settings.viewmodel.ApiKeysViewModel
import com.garfiec.librechat.feature.settings.viewmodel.FavoritesViewModel
import com.garfiec.librechat.feature.settings.viewmodel.McpViewModel
import com.garfiec.librechat.feature.settings.viewmodel.MemoriesViewModel
import com.garfiec.librechat.feature.settings.viewmodel.PresetManagerViewModel
import com.garfiec.librechat.feature.settings.viewmodel.RoleSkillsAdminViewModel
import com.garfiec.librechat.feature.settings.viewmodel.SettingsViewModel
import com.garfiec.librechat.feature.settings.viewmodel.providerkeys.ProviderKeysViewModel
import com.garfiec.librechat.feature.settings.viewmodel.providerkeys.SetProviderKeyViewModel
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

expect val settingsPlatformModule: Module

val settingsModule = module {
    includes(settingsPlatformModule)

    // Explicit block (not viewModelOf) so the IO dispatcher can be resolved by
    // qualifier — constructor-DSL resolves CoroutineDispatcher by type only.
    viewModel {
        SettingsViewModel(
            contentReader = get(),
            cacheCleaner = get(),
            userRepository = get(),
            authRepository = get(),
            conversationRepository = get(),
            themeDataStore = get(),
            serverDataStore = get(),
            settingsDataStore = get(),
            mcpRepository = get(),
            memoryRepository = get(),
            speechSettingsFactory = get(),
            balanceRepository = get(),
            shareRepository = get(),
            keyRepository = get(),
            roleRepository = get(),
            permissionGate = get(),
            configRepository = get(),
            diagnosticLogRepository = get(),
            appInfo = get(),
            ioDispatcher = get(KoinQualifiers.IO),
        )
    }
    viewModelOf(::ApiKeysViewModel)
    viewModelOf(::FavoritesViewModel)
    viewModelOf(::MemoriesViewModel)
    viewModelOf(::McpViewModel)
    viewModelOf(::PresetManagerViewModel)
    viewModelOf(::ProviderKeysViewModel)
    viewModelOf(::RoleSkillsAdminViewModel)

    // viewModelOf has no overload that accepts ParametersHolder, so the runtime
    // endpointName parameter forces the lambda DSL despite the deprecation hint.
    @Suppress("DeprecatedKoinApi")
    viewModel { params ->
        SetProviderKeyViewModel(
            endpointName = params.get(),
            keyRepository = get(),
            configRepository = get(),
        )
    }
}
