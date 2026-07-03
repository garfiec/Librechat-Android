package com.garfiec.librechat.feature.chat.di

import android.app.Application
import android.content.Context
import com.garfiec.librechat.core.common.identity.ActiveAccountProvider
import com.garfiec.librechat.core.common.network.ConnectivityObserver
import com.garfiec.librechat.core.data.datastore.ServerDataStore
import com.garfiec.librechat.core.data.datastore.SettingsDataStore
import com.garfiec.librechat.core.data.repository.AgentRepository
import com.garfiec.librechat.core.data.repository.ChatRepository
import com.garfiec.librechat.core.data.repository.ConfigRepository
import com.garfiec.librechat.core.data.repository.ConversationRepository
import com.garfiec.librechat.core.data.repository.DraftRepository
import com.garfiec.librechat.core.data.repository.EndpointTokenRepository
import com.garfiec.librechat.core.data.repository.FavoritesRepository
import com.garfiec.librechat.core.data.repository.FileRepository
import com.garfiec.librechat.core.data.repository.KeyRepository
import com.garfiec.librechat.core.data.repository.McpRepository
import com.garfiec.librechat.core.data.repository.MessageRepository
import com.garfiec.librechat.core.data.repository.PresetRepository
import com.garfiec.librechat.core.data.repository.PromptRepository
import com.garfiec.librechat.core.data.repository.RoleRepository
import com.garfiec.librechat.core.data.repository.ShareRepository
import com.garfiec.librechat.core.data.repository.SpeechRepository
import com.garfiec.librechat.core.data.repository.UserRepository
import com.garfiec.librechat.core.data.util.PermissionGate
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.serialization.json.Json
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
                EndpointTokenRepository::class,
                FavoritesRepository::class,
                FileRepository::class,
                KeyRepository::class,
                PresetRepository::class,
                PromptRepository::class,
                RoleRepository::class,
                PermissionGate::class,
                ShareRepository::class,
                SpeechRepository::class,
                McpRepository::class,
                UserRepository::class,
                ConnectivityObserver::class,
                ActiveAccountProvider::class,
                ServerDataStore::class,
                SettingsDataStore::class,
                // Dispatchers are supplied via Koin qualifiers in explicit blocks; verify()
                // can't see qualifiers, so whitelist the type as externally provided.
                CoroutineDispatcher::class,
                // Provided by core:network NetworkModule (SSE/tool-call JSON parsing).
                Json::class,
            ),
        )
    }
}
