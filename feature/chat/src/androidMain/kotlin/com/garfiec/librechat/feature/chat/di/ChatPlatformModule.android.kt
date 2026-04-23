package com.garfiec.librechat.feature.chat.di

import com.garfiec.librechat.feature.chat.viewmodel.ChatViewModel
import com.garfiec.librechat.feature.chat.viewmodel.delegate.AndroidDelegateFactory
import com.garfiec.librechat.feature.chat.viewmodel.delegate.PlatformDelegateFactory
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

actual val chatPlatformModule: Module = module {
    single<PlatformDelegateFactory> {
        AndroidDelegateFactory(
            appContext = androidContext(),
            fileRepository = get(),
            speechRepository = get(),
            settingsDataStore = get(),
        )
    }

    viewModel { params ->
        ChatViewModel(
            initialConversationId = params.getOrNull(),
            agentRepository = get(),
            chatRepository = get(),
            messageRepository = get(),
            configRepository = get(),
            conversationRepository = get(),
            draftRepository = get(),
            favoritesRepository = get(),
            presetRepository = get(),
            promptRepository = get(),
            shareRepository = get(),
            mcpRepository = get(),
            userRepository = get(),
            connectivityObserver = get(),
            serverDataStore = get(),
            settingsDataStore = get(),
            platformDelegateFactory = get(),
        )
    }
}
