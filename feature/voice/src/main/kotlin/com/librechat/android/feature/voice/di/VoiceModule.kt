package com.librechat.android.feature.voice.di

import com.librechat.android.core.data.datastore.SettingsDataStore
import com.librechat.android.feature.voice.viewmodel.VoiceSessionViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val voiceModule = module {
    viewModel {
        VoiceSessionViewModel(
            appContext = androidContext(),
            chatRepository = get(),
            speechRepository = get(),
            conversationRepository = get(),
            messageRepository = get(),
            userRepository = get(),
            configRepository = get(),
            settingsDataStore = get<SettingsDataStore>(),
        )
    }
}
