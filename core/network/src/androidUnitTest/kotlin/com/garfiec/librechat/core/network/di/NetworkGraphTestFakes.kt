package com.garfiec.librechat.core.network.di

import com.garfiec.librechat.core.common.identity.AccountId
import com.garfiec.librechat.core.common.identity.AccountState
import com.garfiec.librechat.core.common.identity.ActiveAccountProvider
import com.garfiec.librechat.core.common.identity.InMemoryActiveAccountProvider
import com.garfiec.librechat.core.common.network.RequestActivityTracker
import com.garfiec.librechat.core.logging.redact.LogRedactor
import com.garfiec.librechat.core.network.client.LibreChatHttpClient
import com.garfiec.librechat.core.network.client.RefreshResult
import com.garfiec.librechat.core.network.client.ServerHeadersProvider
import com.garfiec.librechat.core.network.client.ServerUrlProvider
import com.garfiec.librechat.core.network.client.SessionEndReason
import com.garfiec.librechat.core.network.client.TokenManager
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngineFactory
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.json.Json
import org.koin.core.KoinApplication
import org.koin.core.qualifier.Qualifier
import org.koin.dsl.koinApplication
import org.koin.dsl.module

/**
 * Builds the real [networkModule] against fakes for the bindings other modules own.
 *
 * Shared by the install tests, which exist to catch a plugin that is missing from a client in the
 * graph the app actually builds — something a test that constructs its own client can never observe.
 */
internal object NetworkGraphTestFakes {

    private class FakeServerUrlProvider : ServerUrlProvider {
        override fun getBaseUrl(): String = "https://chat.example.com"
    }

    private class FakeServerHeadersProvider : ServerHeadersProvider {
        override suspend fun awaitWarm() = Unit
        override fun headersFor(baseUrl: String): Map<String, String> = emptyMap()
    }

    private class FakeTokenManager : TokenManager {
        private val expired = MutableSharedFlow<SessionEndReason>()
        override val sessionExpiredFlow: SharedFlow<SessionEndReason> = expired
        override val isAuthenticated: Boolean = false
        override suspend fun getAccessToken(): String? = null
        override suspend fun setTokens(accessToken: String, refreshToken: String) = Unit
        override suspend fun refreshAccessToken(): RefreshResult = RefreshResult.Transient
        override suspend fun clearTokens() = Unit
        override suspend fun getAccessTokenFor(accountId: String): String? = null
        override suspend fun getStagedAccessToken(): String? = null
        override suspend fun clearStagedTokens() = Unit
        override suspend fun selectAccount(accountId: String) = Unit
        override suspend fun removeAccount(accountId: String) = Unit
        override suspend fun refreshAccessTokenFor(accountId: String, baseUrl: String): RefreshResult =
            RefreshResult.Transient

        override suspend fun onAccountResolved(accountId: String) = Unit
        override fun emitSessionExpired(expiredAccountId: String?, reason: SessionEndReason) = Unit
    }

    fun koinApp(): KoinApplication = koinApplication {
        modules(
            networkModule,
            module {
                single<ServerUrlProvider> { FakeServerUrlProvider() }
                single<ServerHeadersProvider> { FakeServerHeadersProvider() }
                single<TokenManager> { FakeTokenManager() }
                // Bound in :core:data, :core:logging and :core:common in the real graph.
                single<ActiveAccountProvider> {
                    InMemoryActiveAccountProvider(AccountState.Resolved(AccountId("srv-1:user-a")))
                }
                single { LogRedactor() }
                single { RequestActivityTracker() }
            },
        )
    }

    /** Resolves a client from [app]; null [qualifier] is the main client. */
    fun client(app: KoinApplication, qualifier: Qualifier?): HttpClient =
        if (qualifier == null) app.koin.get() else app.koin.get(qualifier)

    /**
     * The main client over an arbitrary engine, built through the real factory.
     *
     * Behavioural tests go through [LibreChatHttpClient.create] rather than hand-assembling a client
     * so they observe the production plugin *order* — which is what decides whether a retried call
     * counts once or three times.
     */
    fun mainClient(
        engineFactory: HttpClientEngineFactory<*>,
        requestActivityTracker: RequestActivityTracker,
    ): HttpClient = LibreChatHttpClient.create(
        engineFactory = engineFactory,
        json = Json { ignoreUnknownKeys = true },
        tokenManager = FakeTokenManager(),
        serverUrlProvider = FakeServerUrlProvider(),
        redactor = LogRedactor(),
        serverHeadersProvider = FakeServerHeadersProvider(),
        requestActivityTracker = requestActivityTracker,
    )
}
