package com.garfiec.librechat.core.data.di

import android.app.Application
import android.content.Context
import androidx.datastore.core.DataStore
import com.garfiec.librechat.core.common.network.ConnectivityObserver
import com.garfiec.librechat.core.network.api.AgentToolsApi
import com.garfiec.librechat.core.network.api.AgentsApi
import com.garfiec.librechat.core.network.api.ApiKeysApi
import com.garfiec.librechat.core.network.api.AuthApi
import com.garfiec.librechat.core.network.api.BalanceApi
import com.garfiec.librechat.core.network.api.BannerApi
import com.garfiec.librechat.core.network.api.ChatApi
import com.garfiec.librechat.core.network.api.ConfigApi
import com.garfiec.librechat.core.network.api.ConversationsApi
import com.garfiec.librechat.core.network.api.EndpointTokenApi
import com.garfiec.librechat.core.network.api.FavoritesApi
import com.garfiec.librechat.core.network.api.FilesApi
import com.garfiec.librechat.core.network.api.FilesExtApi
import com.garfiec.librechat.core.network.api.KeysApi
import com.garfiec.librechat.core.network.api.McpApi
import com.garfiec.librechat.core.network.api.MemoriesApi
import com.garfiec.librechat.core.network.api.MessagesApi
import com.garfiec.librechat.core.network.api.PermissionsApi
import com.garfiec.librechat.core.network.api.PresetsApi
import com.garfiec.librechat.core.network.api.ProjectsApi
import com.garfiec.librechat.core.network.api.PromptsApi
import com.garfiec.librechat.core.network.api.RolesApi
import com.garfiec.librechat.core.network.api.ShareApi
import com.garfiec.librechat.core.network.api.SkillsApi
import com.garfiec.librechat.core.network.api.SpeechApi
import com.garfiec.librechat.core.network.api.TagsApi
import com.garfiec.librechat.core.network.api.UserApi
import com.garfiec.librechat.core.network.client.SwitchGate
import com.garfiec.librechat.core.network.sse.SseClient
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.Json
import org.junit.Test
import org.koin.test.verify.verify

class DataModuleVerificationTest {
    @Test
    fun verifyDataModule() {
        dataModule.verify(
            extraTypes = listOf(
                Context::class,
                Application::class,
                Lazy::class,
                HttpClient::class,
                Json::class,
                CoroutineDispatcher::class,
                CoroutineScope::class,
                ConnectivityObserver::class,
                SseClient::class,
                AuthApi::class,
                UserApi::class,
                ChatApi::class,
                ConversationsApi::class,
                EndpointTokenApi::class,
                ProjectsApi::class,
                MessagesApi::class,
                FavoritesApi::class,
                FilesApi::class,
                FilesExtApi::class,
                AgentToolsApi::class,
                AgentsApi::class,
                PermissionsApi::class,
                PresetsApi::class,
                PromptsApi::class,
                RolesApi::class,
                TagsApi::class,
                ShareApi::class,
                ConfigApi::class,
                BalanceApi::class,
                SkillsApi::class,
                KeysApi::class,
                ApiKeysApi::class,
                McpApi::class,
                MemoriesApi::class,
                SpeechApi::class,
                BannerApi::class,
                DataStore::class,
                // AccountSwitcher pulls the switch barrier from :core:network (cross-module).
                SwitchGate::class,
            ),
        )
    }
}
