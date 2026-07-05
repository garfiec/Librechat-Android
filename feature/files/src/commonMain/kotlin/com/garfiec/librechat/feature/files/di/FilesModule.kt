package com.garfiec.librechat.feature.files.di

import com.garfiec.librechat.core.common.di.KoinQualifiers
import com.garfiec.librechat.feature.files.viewmodel.FilesViewModel
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

expect val filesPlatformModule: Module

val filesModule = module {
    includes(filesPlatformModule)
    viewModel {
        FilesViewModel(
            fileRepository = get(),
            fileReader = get(),
            serverDataStore = get(),
            settingsDataStore = get(),
            ioDispatcher = get(KoinQualifiers.IO),
        )
    }
}
