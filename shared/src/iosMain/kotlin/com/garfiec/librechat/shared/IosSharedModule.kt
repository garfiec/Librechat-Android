package com.garfiec.librechat.shared

import com.garfiec.librechat.core.common.di.KoinQualifiers
import com.garfiec.librechat.core.common.di.commonModule
import com.garfiec.librechat.core.data.di.dataModule
import com.garfiec.librechat.core.logging.di.loggingModule
import com.garfiec.librechat.core.network.api.AgentsApi
import com.garfiec.librechat.core.network.api.ApiKeysApi
import com.garfiec.librechat.core.network.api.AuthApi
import com.garfiec.librechat.core.network.api.BalanceApi
import com.garfiec.librechat.core.network.api.BannerApi
import com.garfiec.librechat.core.network.api.ChatApi
import com.garfiec.librechat.core.network.api.ConfigApi
import com.garfiec.librechat.core.network.api.ConversationsApi
import com.garfiec.librechat.core.network.api.FavoritesApi
import com.garfiec.librechat.core.network.api.FilesApi
import com.garfiec.librechat.core.network.api.FilesExtApi
import com.garfiec.librechat.core.network.api.KeysApi
import com.garfiec.librechat.core.network.api.McpApi
import com.garfiec.librechat.core.network.api.MemoriesApi
import com.garfiec.librechat.core.network.api.MessagesApi
import com.garfiec.librechat.core.network.api.PresetsApi
import com.garfiec.librechat.core.network.api.PromptsApi
import com.garfiec.librechat.core.network.api.RolesApi
import com.garfiec.librechat.core.network.api.SearchApi
import com.garfiec.librechat.core.network.api.ShareApi
import com.garfiec.librechat.core.network.api.SpeechApi
import com.garfiec.librechat.core.network.api.TagsApi
import com.garfiec.librechat.core.network.api.UserApi
import com.garfiec.librechat.core.network.client.LibreChatHttpClient
import com.garfiec.librechat.core.network.client.ServerUrlProvider
import com.garfiec.librechat.core.network.client.ServerUrlReadyPlugin
import com.garfiec.librechat.core.network.client.TokenManager
import com.garfiec.librechat.core.network.sse.SseClient
import com.garfiec.librechat.core.network.sse.SseHttpTransport
import com.garfiec.librechat.feature.agents.di.agentsModule
import com.garfiec.librechat.feature.auth.di.authModule
import com.garfiec.librechat.feature.chat.di.chatModule
import com.garfiec.librechat.feature.conversations.di.conversationsModule
import com.garfiec.librechat.feature.files.di.filesModule
import com.garfiec.librechat.feature.settings.di.settingsModule
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.takeFrom
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

/**
 * iOS-specific Koin module providing platform implementations and HTTP clients.
 * Includes dataModule which provides repositories, DAOs, DataStore, and token storage.
 */
val iosSharedModule = module {

    includes(commonModule)
    includes(loggingModule)
    includes(dataModule)
    includes(authModule)
    includes(chatModule)
    includes(conversationsModule)
    includes(settingsModule)
    includes(agentsModule)
    includes(filesModule)

    single {
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = false
            explicitNulls = false
            coerceInputValues = true
        }
    }

    // Main HttpClient (with auth interceptor)
    single {
        LibreChatHttpClient.create(
            engineFactory = Darwin,
            json = get(),
            tokenManager = get(),
            serverUrlProvider = get(),
            redactor = get(),
        )
    }

    // Refresh HttpClient (no auth interceptor, short timeout)
    single(KoinQualifiers.Refresh) {
        val json = get<Json>()
        val serverUrlProvider = get<ServerUrlProvider>()
        HttpClient(Darwin) {
            install(ContentNegotiation) { json(json) }
            install(ServerUrlReadyPlugin) {
                this.serverUrlProvider = serverUrlProvider
            }
            install(HttpTimeout) {
                requestTimeoutMillis = 15_000
                connectTimeoutMillis = 10_000
            }
            defaultRequest {
                val baseUrl = serverUrlProvider.getBaseUrl()
                if (baseUrl.isNotEmpty()) {
                    url.takeFrom(baseUrl)
                }
                contentType(ContentType.Application.Json)
            }
        }
    }

    // API services (all take HttpClient as constructor parameter)
    singleOf(::AuthApi)
    singleOf(::ChatApi)
    singleOf(::ConfigApi)
    singleOf(::ConversationsApi)
    singleOf(::FilesApi)
    singleOf(::FilesExtApi)
    singleOf(::MessagesApi)
    singleOf(::UserApi)
    singleOf(::AgentsApi)
    singleOf(::FavoritesApi)
    singleOf(::PresetsApi)
    singleOf(::PromptsApi)
    singleOf(::RolesApi)
    singleOf(::TagsApi)
    singleOf(::ShareApi)
    singleOf(::SearchApi)
    singleOf(::BalanceApi)
    singleOf(::BannerApi)
    singleOf(::KeysApi)
    singleOf(::ApiKeysApi)
    singleOf(::McpApi)
    singleOf(::MemoriesApi)
    singleOf(::SpeechApi)
    single { SseHttpTransport(get(), get()) }
    singleOf(::SseClient)

    // SDK facade
    single {
        LibreChatSDK(
            authApi = get(),
            chatApi = get(),
            sseClient = get(),
            tokenManager = get(),
        )
    }
}
