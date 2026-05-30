package com.garfiec.librechat.core.network.di

import com.garfiec.librechat.core.common.di.KoinQualifiers
import com.garfiec.librechat.core.network.api.AgentToolsApi
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
import com.garfiec.librechat.core.network.api.PermissionsApi
import com.garfiec.librechat.core.network.api.PresetsApi
import com.garfiec.librechat.core.network.api.PromptsApi
import com.garfiec.librechat.core.network.api.RolesApi
import com.garfiec.librechat.core.network.api.SearchApi
import com.garfiec.librechat.core.network.api.ShareApi
import com.garfiec.librechat.core.network.api.SpeechApi
import com.garfiec.librechat.core.network.api.TagsApi
import com.garfiec.librechat.core.network.api.UserApi
import com.garfiec.librechat.core.network.client.AuthInterceptorPlugin
import com.garfiec.librechat.core.network.client.LibreChatHttpClient
import com.garfiec.librechat.core.network.client.ServerUrlProvider
import com.garfiec.librechat.core.network.client.TokenManager
import com.garfiec.librechat.core.network.sse.SseClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.takeFrom
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import org.koin.core.module.Module
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

expect val networkPlatformModule: Module

val networkModule = module {
    includes(networkPlatformModule)

    single {
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = false
            explicitNulls = false
            coerceInputValues = true
        }
    }

    single {
        LibreChatHttpClient.create(
            engineFactory = get<HttpClientEngineFactory<*>>(),
            json = get(),
            tokenManager = get(),
            serverUrlProvider = get(),
            redactor = get(),
        )
    }

    single(KoinQualifiers.Streaming) {
        val tokenManager = get<TokenManager>()
        val serverUrlProvider = get<ServerUrlProvider>()
        val engineFactory = get<HttpClientEngineFactory<*>>()
        HttpClient(engineFactory) {
            install(AuthInterceptorPlugin) {
                this.tokenManager = tokenManager
            }
            install(HttpTimeout) {
                connectTimeoutMillis = 10_000
                requestTimeoutMillis = Long.MAX_VALUE
                socketTimeoutMillis = Long.MAX_VALUE
            }
            defaultRequest {
                val baseUrl = serverUrlProvider.getBaseUrl()
                if (baseUrl.isNotEmpty()) {
                    url.takeFrom(baseUrl)
                }
                headers.append(HttpHeaders.UserAgent, LibreChatHttpClient.BROWSER_USER_AGENT)
            }
        }
    }

    single(KoinQualifiers.Refresh) {
        val json = get<Json>()
        val serverUrlProvider = get<ServerUrlProvider>()
        val engineFactory = get<HttpClientEngineFactory<*>>()
        HttpClient(engineFactory) {
            install(ContentNegotiation) { json(json) }
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

    // SSE — SseHttpTransport is provided by networkPlatformModule because its
    // constructor signature differs between Android (takes the Ktor streaming
    // HttpClient) and iOS (takes NWConnection dependencies in Phase 2).
    singleOf(::SseClient)

    // API services
    singleOf(::AgentsApi)
    singleOf(::AgentToolsApi)
    singleOf(::ApiKeysApi)
    singleOf(::AuthApi)
    singleOf(::BalanceApi)
    singleOf(::BannerApi)
    singleOf(::ChatApi)
    singleOf(::ConfigApi)
    singleOf(::ConversationsApi)
    singleOf(::FavoritesApi)
    singleOf(::FilesApi)
    singleOf(::FilesExtApi)
    singleOf(::KeysApi)
    singleOf(::McpApi)
    singleOf(::MemoriesApi)
    singleOf(::MessagesApi)
    singleOf(::PermissionsApi)
    singleOf(::PresetsApi)
    singleOf(::PromptsApi)
    singleOf(::RolesApi)
    singleOf(::SearchApi)
    singleOf(::ShareApi)
    singleOf(::SpeechApi)
    singleOf(::TagsApi)
    singleOf(::UserApi)
}
