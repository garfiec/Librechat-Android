package com.garfiec.librechat.feature.chat.di

import android.app.Application
import android.content.Context
import com.garfiec.librechat.core.common.network.ConnectivityObserver
import com.garfiec.librechat.core.data.datastore.ServerDataStore
import com.garfiec.librechat.core.data.datastore.SettingsDataStore
import com.garfiec.librechat.core.data.repository.AgentRepository
import com.garfiec.librechat.core.data.repository.ChatRepository
import com.garfiec.librechat.core.data.repository.ConfigRepository
import com.garfiec.librechat.core.data.repository.ConversationRepository
import com.garfiec.librechat.core.data.repository.DraftRepository
import com.garfiec.librechat.core.data.repository.FavoritesRepository
import com.garfiec.librechat.core.data.repository.FileRepository
import com.garfiec.librechat.core.data.repository.McpRepository
import com.garfiec.librechat.core.data.repository.MessageRepository
import com.garfiec.librechat.core.data.repository.PresetRepository
import com.garfiec.librechat.core.data.repository.PromptRepository
import com.garfiec.librechat.core.data.repository.ShareRepository
import com.garfiec.librechat.core.data.repository.SpeechRepository
import com.garfiec.librechat.core.data.repository.UserRepository
import org.junit.Test
import org.koin.test.verify.verify

class ChatModuleVerificationTest {
    @Test
    fun verifyChatModule() {
        chatModule.verify(
            extraTypes = listOf(
                Context::class,
                Application::class,
                AgentRepository::class,
                ChatRepository::class,
                MessageRepository::class,
                ConfigRepository::class,
                ConversationRepository::class,
                DraftRepository::class,
                FavoritesRepository::class,
                FileRepository::class,
                PresetRepository::class,
                PromptRepository::class,
                ShareRepository::class,
                SpeechRepository::class,
                McpRepository::class,
                UserRepository::class,
                ConnectivityObserver::class,
                ServerDataStore::class,
                SettingsDataStore::class,
            ),
        )
    }
}
