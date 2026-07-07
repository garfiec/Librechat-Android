package com.garfiec.librechat.feature.chat.di

import com.garfiec.librechat.core.common.di.KoinQualifiers
import com.garfiec.librechat.feature.chat.components.artifact.ArtifactViewerHandoff
import com.garfiec.librechat.feature.chat.navigation.ModelShortcutBus
import com.garfiec.librechat.feature.chat.prompts.PromptEditorViewModel
import com.garfiec.librechat.feature.chat.prompts.PromptsViewModel
import com.garfiec.librechat.feature.chat.viewmodel.ConversationMediaViewModel
import com.garfiec.librechat.feature.chat.viewmodel.NewChatSelectionHandoff
import com.garfiec.librechat.feature.chat.viewmodel.ServerFileSelectionHandoff
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val chatModule = module {
    includes(chatPlatformModule)
    single { NewChatSelectionHandoff() }
    single { ServerFileSelectionHandoff() }
    single { ArtifactViewerHandoff() }
    single { ModelShortcutBus() }
    viewModelOf(::PromptsViewModel)
    // Koin's constructor-DSL (`viewModelOf`) wires every argument via `get()` and cannot read
    // values passed through `parametersOf`. This VM receives its `initialGroupId` from the
    // navigation layer via `parametersOf`, so the lambda-form `viewModel { params -> ... }` is
    // the only DSL that works here. Detekt's `DeprecatedKoinApi` is a blanket stylistic rule,
    // not a real `@Deprecated` API, so we suppress it in the narrow place it applies.
    @Suppress("DeprecatedKoinApi")
    viewModel { params ->
        PromptEditorViewModel(
            promptRepository = get(),
            initialGroupId = params.getOrNull(),
        )
    }
    // conversationId arrives from the navigation layer via parametersOf — same reason as above.
    @Suppress("DeprecatedKoinApi")
    viewModel { params ->
        ConversationMediaViewModel(
            conversationId = params.get(),
            messageRepository = get(),
            fileRepository = get(),
            userRepository = get(),
            serverDataStore = get(),
            defaultDispatcher = get(KoinQualifiers.Default),
        )
    }
}

expect val chatPlatformModule: Module
