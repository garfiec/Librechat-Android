package com.garfiec.librechat

import android.app.Application
import android.content.Context
import com.garfiec.librechat.core.common.AppInfo
import com.garfiec.librechat.core.common.identity.ActiveAccountProvider
import com.garfiec.librechat.core.common.network.ConnectivityObserver
import com.garfiec.librechat.core.data.datastore.AccountRoster
import com.garfiec.librechat.core.data.datastore.ConfigCacheDataStore
import com.garfiec.librechat.core.data.datastore.ServerDataStore
import com.garfiec.librechat.core.data.datastore.SettingsDataStore
import com.garfiec.librechat.core.data.datastore.ThemeDataStore
import com.garfiec.librechat.core.data.repository.AccountSwitcher
import com.garfiec.librechat.core.data.repository.AgentRepository
import com.garfiec.librechat.core.data.repository.AgentToolsRepository
import com.garfiec.librechat.core.data.repository.ApiKeyRepository
import com.garfiec.librechat.core.data.repository.AuthRepository
import com.garfiec.librechat.core.data.repository.BalanceRepository
import com.garfiec.librechat.core.data.repository.BannerRepository
import com.garfiec.librechat.core.data.repository.ChatRepository
import com.garfiec.librechat.core.data.repository.ConfigRepository
import com.garfiec.librechat.core.data.repository.ConversationRepository
import com.garfiec.librechat.core.data.repository.DraftRepository
import com.garfiec.librechat.core.data.repository.EndpointTokenRepository
import com.garfiec.librechat.core.data.repository.FavoritesRepository
import com.garfiec.librechat.core.data.repository.FileRepository
import com.garfiec.librechat.core.data.repository.KeyRepository
import com.garfiec.librechat.core.data.repository.McpRepository
import com.garfiec.librechat.core.data.repository.MemoryRepository
import com.garfiec.librechat.core.data.repository.MessageRepository
import com.garfiec.librechat.core.data.repository.PermissionsRepository
import com.garfiec.librechat.core.data.repository.PresetRepository
import com.garfiec.librechat.core.data.repository.ProjectRepository
import com.garfiec.librechat.core.data.repository.PromptRepository
import com.garfiec.librechat.core.data.repository.RoleRepository
import com.garfiec.librechat.core.data.repository.SearchRepository
import com.garfiec.librechat.core.data.repository.ShareRepository
import com.garfiec.librechat.core.data.repository.SkillsRepository
import com.garfiec.librechat.core.data.repository.SpeechRepository
import com.garfiec.librechat.core.data.repository.TagRepository
import com.garfiec.librechat.core.data.repository.UserRepository
import com.garfiec.librechat.core.data.util.PermissionGate
import com.garfiec.librechat.core.data.util.SessionTask
import com.garfiec.librechat.core.data.util.SessionTaskRunner
import com.garfiec.librechat.core.logging.DiagnosticLogRepository
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
import com.garfiec.librechat.core.network.api.ShareApi
import com.garfiec.librechat.core.network.api.SkillsApi
import com.garfiec.librechat.core.network.api.SpeechApi
import com.garfiec.librechat.core.network.api.TagsApi
import com.garfiec.librechat.core.network.api.UserApi
import com.garfiec.librechat.core.network.client.AccountReadyGate
import com.garfiec.librechat.core.network.client.SecureTokenStorage
import com.garfiec.librechat.core.network.client.ServerUrlProvider
import com.garfiec.librechat.core.network.client.SwitchGate
import com.garfiec.librechat.core.network.client.TokenManager
import com.garfiec.librechat.core.network.sse.SseClient
import com.garfiec.librechat.feature.auth.oauth.OAuthLauncher
import com.garfiec.librechat.feature.conversations.export.ConversationExporter
import com.garfiec.librechat.feature.files.platform.FileReader
import com.garfiec.librechat.shared.di.sharedKoinModules
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import org.junit.Test
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.test.verify.verify
import java.io.File
import kotlin.reflect.KClass

class KoinGraphVerificationTest {

    /**
     * Integration-level Koin graph verification over the shared module list
     * ([sharedKoinModules]) — the same list both platforms start from.
     *
     * Koin's verify() operates per-module and cannot resolve definitions
     * from other modules. This test whitelists all cross-module and
     * framework types so every module is verified in a single test class.
     * If a type is renamed or removed, this test will catch it.
     *
     * Scope: this JVM test resolves the shared list against **Android** actuals
     * (verify() runs on the JVM, so `networkModule.includes(networkPlatformModule)`
     * binds the Android engine). The iOS actuals and the iOS-only `LibreChatSDK`
     * binding are covered by `IosKoinGraphTest` (`:shared:iosSimulatorArm64Test`).
     */
    @OptIn(KoinExperimentalAPI::class)
    @Test
    fun verifyFullKoinGraph() {
        val extraTypes = mutableListOf<KClass<*>>(
            // Android framework
            Context::class,
            Application::class,
            // core:common provides
            CoroutineDispatcher::class,
            CoroutineScope::class,
            ConnectivityObserver::class,
            ActiveAccountProvider::class,
            AppInfo::class,
            // core:logging provides
            DiagnosticLogRepository::class,
            // core:network provides
            TokenManager::class,
            SecureTokenStorage::class,
            ServerUrlProvider::class,
            AccountReadyGate::class,
            SwitchGate::class,
            SseClient::class,
            AgentToolsApi::class,
            AgentsApi::class,
            ApiKeysApi::class,
            AuthApi::class,
            BalanceApi::class,
            BannerApi::class,
            ChatApi::class,
            ConfigApi::class,
            ConversationsApi::class,
            EndpointTokenApi::class,
            FavoritesApi::class,
            FilesApi::class,
            FilesExtApi::class,
            KeysApi::class,
            McpApi::class,
            MemoriesApi::class,
            MessagesApi::class,
            PermissionsApi::class,
            PresetsApi::class,
            ProjectsApi::class,
            PromptsApi::class,
            ShareApi::class,
            SkillsApi::class,
            SpeechApi::class,
            TagsApi::class,
            UserApi::class,
            // core:data provides
            ConfigCacheDataStore::class,
            ServerDataStore::class,
            AccountRoster::class,
            AccountSwitcher::class,
            SettingsDataStore::class,
            ThemeDataStore::class,
            AgentRepository::class,
            AgentToolsRepository::class,
            ApiKeyRepository::class,
            AuthRepository::class,
            BalanceRepository::class,
            BannerRepository::class,
            ChatRepository::class,
            ConfigRepository::class,
            ConversationRepository::class,
            DraftRepository::class,
            EndpointTokenRepository::class,
            FavoritesRepository::class,
            FileRepository::class,
            KeyRepository::class,
            McpRepository::class,
            MemoryRepository::class,
            MessageRepository::class,
            PermissionsRepository::class,
            PresetRepository::class,
            ProjectRepository::class,
            PromptRepository::class,
            RoleRepository::class,
            SearchRepository::class,
            ShareRepository::class,
            SkillsRepository::class,
            SpeechRepository::class,
            TagRepository::class,
            UserRepository::class,
            PermissionGate::class,
            SessionTask::class,
            SessionTaskRunner::class,
            // feature:auth platform provides
            OAuthLauncher::class,
            // feature:files platform provides
            FileReader::class,
            // feature:conversations provides (consumed cross-module by shared NavHostViewModel)
            ConversationExporter::class,
            // Wrappers/DSL types that verify can't resolve via constructor
            Lazy::class,
            File::class,
            // ServerUrlViewModel's addAccount mode flag, injected via parametersOf
            Boolean::class,
        )

        // Types whose libraries aren't on the app test classpath (transitive
        // implementation deps). Resolve via reflection at runtime.
        val reflectionTypes = listOf(
            "io.ktor.client.HttpClient",
            "io.ktor.client.engine.HttpClientEngine",
            "io.ktor.client.engine.HttpClientEngineConfig",
            "kotlinx.serialization.json.Json",
            "androidx.datastore.core.DataStore",
        )
        reflectionTypes.forEach { className ->
            extraTypes.add(Class.forName(className).kotlin)
        }

        sharedKoinModules.forEach { module ->
            module.verify(extraTypes = extraTypes)
        }
    }
}
