package com.garfiec.librechat.feature.chat.di

import com.garfiec.librechat.core.common.di.KoinQualifiers
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
            ioDispatcher = get(KoinQualifiers.IO),
        )
    }

    viewModel { params ->
        ChatViewModel(
            // Positional: [0] conversationId, [1] initialAgentId — both String?, so
            // type-based getOrNull() is ambiguous; read by index from values.
            initialConversationId = params.values.getOrNull(0) as String?,
            initialAgentId = params.values.getOrNull(1) as String?,
            // [2] isTemporary route flag (temp-chat data-at-rest guard); absent on non-temp callers.
            initialIsTemporary = params.values.getOrNull(2) as? Boolean ?: false,
            // [3] initialEndpoint, [4] initialModel — model-shortcut pre-select; absent otherwise.
            initialEndpoint = params.values.getOrNull(3) as String?,
            initialModel = params.values.getOrNull(4) as String?,
            agentRepository = get(),
            chatRepository = get(),
            messageRepository = get(),
            fileRepository = get(),
            configRepository = get(),
            conversationRepository = get(),
            endpointTokenRepository = get(),
            draftRepository = get(),
            favoritesRepository = get(),
            keyRepository = get(),
            presetRepository = get(),
            promptRepository = get(),
            shareRepository = get(),
            mcpRepository = get(),
            userRepository = get(),
            roleRepository = get(),
            permissionGate = get(),
            connectivityObserver = get(),
            activeAccountProvider = get(),
            serverDataStore = get(),
            settingsDataStore = get(),
            platformDelegateFactory = get(),
            json = get(),
            defaultDispatcher = get(KoinQualifiers.Default),
            selectionHandoff = get(),
            serverFileSelectionHandoff = get(),
        )
    }
}
