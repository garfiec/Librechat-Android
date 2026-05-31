package com.garfiec.librechat.feature.settings.di

import com.garfiec.librechat.core.common.di.KoinQualifiers
import com.garfiec.librechat.feature.settings.util.AndroidCacheCleaner
import com.garfiec.librechat.feature.settings.util.AndroidContentReader
import com.garfiec.librechat.feature.settings.util.ContentReader
import com.garfiec.librechat.feature.settings.util.PlatformCacheCleaner
import com.garfiec.librechat.feature.settings.viewmodel.delegate.SpeechSettingsDelegate
import com.garfiec.librechat.feature.settings.viewmodel.delegate.SpeechSettingsFactory
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.bind
import org.koin.dsl.module

actual val settingsPlatformModule: Module = module {
    single { AndroidContentReader(androidContext()) } bind ContentReader::class
    single { AndroidCacheCleaner(androidContext()) } bind PlatformCacheCleaner::class
    single<SpeechSettingsFactory> {
        SpeechSettingsFactory { stateHandle ->
            SpeechSettingsDelegate(
                stateHandle = stateHandle,
                context = androidContext(),
                speechRepository = get(),
                settingsDataStore = get(),
                ioDispatcher = get(KoinQualifiers.IO),
            )
        }
    }
}
