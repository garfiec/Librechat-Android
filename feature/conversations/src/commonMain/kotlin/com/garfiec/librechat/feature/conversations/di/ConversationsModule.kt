package com.garfiec.librechat.feature.conversations.di

import com.garfiec.librechat.core.common.di.KoinQualifiers
import com.garfiec.librechat.feature.conversations.export.ConversationExporter
import com.garfiec.librechat.feature.conversations.export.ConversationImporter
import com.garfiec.librechat.feature.conversations.viewmodel.ArchivedConversationsViewModel
import com.garfiec.librechat.feature.conversations.viewmodel.ConversationListViewModel
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
}
