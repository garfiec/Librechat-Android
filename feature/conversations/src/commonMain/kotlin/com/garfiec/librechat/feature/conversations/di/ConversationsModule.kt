package com.garfiec.librechat.feature.conversations.di

import com.garfiec.librechat.core.common.di.KoinQualifiers
import com.garfiec.librechat.feature.conversations.export.ConversationExporter
import com.garfiec.librechat.feature.conversations.export.ConversationImporter
import com.garfiec.librechat.feature.conversations.viewmodel.ArchivedConversationsViewModel
import com.garfiec.librechat.feature.conversations.viewmodel.ConversationListViewModel
import com.garfiec.librechat.feature.conversations.viewmodel.ProjectChatsViewModel
import com.garfiec.librechat.feature.conversations.viewmodel.ProjectsViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val conversationsModule = module {
    single {
        ConversationExporter(
            conversationRepository = get(),
            messageRepository = get(),
            ioDispatcher = get(KoinQualifiers.IO),
        )
    }
    single {
        ConversationImporter(
            ioDispatcher = get(KoinQualifiers.IO),
        )
    }

    viewModelOf(::ConversationListViewModel)
    viewModelOf(::ArchivedConversationsViewModel)
    viewModelOf(::ProjectsViewModel)
    // projectId arrives from the navigation layer via parametersOf.
    @Suppress("DeprecatedKoinApi")
    viewModel { params ->
        ProjectChatsViewModel(
            projectId = params.get(),
            conversationRepository = get(),
            tagRepository = get(),
            shareRepository = get(),
            conversationExporter = get(),
            roleRepository = get(),
            configRepository = get(),
        )
    }
}
