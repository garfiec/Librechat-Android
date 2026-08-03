package com.garfiec.librechat.core.network.di

import com.garfiec.librechat.core.common.di.KoinQualifiers
import com.garfiec.librechat.core.common.identity.AccountId
import com.garfiec.librechat.core.common.identity.AccountState
import com.garfiec.librechat.core.common.identity.ActiveAccountProvider
import com.garfiec.librechat.core.common.identity.InMemoryActiveAccountProvider
import com.garfiec.librechat.core.logging.redact.LogRedactor
import com.garfiec.librechat.core.network.client.GatewayDetectionPlugin
import com.garfiec.librechat.core.network.client.RefreshResult
import com.garfiec.librechat.core.network.client.ServerHeadersProvider
import com.garfiec.librechat.core.network.client.ServerUrlProvider
import com.garfiec.librechat.core.network.client.TokenManager
import com.google.common.truth.Truth.assertThat
import io.ktor.client.HttpClient
import io.ktor.client.plugins.pluginOrNull
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import org.junit.After
import org.junit.Test
import org.koin.core.KoinApplication
import org.koin.core.qualifier.Qualifier
import org.koin.dsl.koinApplication
import org.koin.dsl.module

/**
 * Which clients carry gateway detection, asserted on the graph the app actually builds. Nothing else
 * in the suite can notice a missing install: a per-client test installs the plugin itself.
 */
class GatewayDetectionInstallTest {

    private var app: KoinApplication? = null

    @After
    fun tearDown() {
        app?.close()
    }

    private class FakeServerUrlProvider : ServerUrlProvider {
        override fun getBaseUrl(): String = "https://chat.example.com"
    }

    private class FakeServerHeadersProvider : ServerHeadersProvider {
        override suspend fun awaitWarm() = Unit
        override fun headersFor(baseUrl: String): Map<String, String> = emptyMap()
    }

    private class FakeTokenManager : TokenManager {
        private val expired = MutableSharedFlow<Unit>()
        override val sessionExpiredFlow: SharedFlow<Unit> = expired
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
        override fun emitSessionExpired(expiredAccountId: String?) = Unit
    }

    private fun client(qualifier: Qualifier?): HttpClient {
        val koin = app ?: koinApplication {
            modules(
                networkModule,
                module {
                    single<ServerUrlProvider> { FakeServerUrlProvider() }
                    single<ServerHeadersProvider> { FakeServerHeadersProvider() }
                    single<TokenManager> { FakeTokenManager() }
                    // Bound in :core:data and :core:logging in the real graph.
                    single<ActiveAccountProvider> {
                        InMemoryActiveAccountProvider(AccountState.Resolved(AccountId("srv-1:user-a")))
                    }
                    single { LogRedactor() }
                },
            )
        }.also { app = it }
        return if (qualifier == null) koin.koin.get() else koin.koin.get(qualifier)
    }

    @Test
    fun `the main client detects gateway rejections`() {
        assertThat(client(null).pluginOrNull(GatewayDetectionPlugin)).isNotNull()
    }

    /** Without it the sign-in page reaches the SSE parser and the chat hangs with no error at all. */
    @Test
    fun `the streaming client detects gateway rejections`() {
        assertThat(client(KoinQualifiers.Streaming).pluginOrNull(GatewayDetectionPlugin)).isNotNull()
    }

    /** Without it the gateway's 302 classifies as a transient server error and the session never recovers. */
    @Test
    fun `the refresh client detects gateway rejections`() {
        assertThat(client(KoinQualifiers.Refresh).pluginOrNull(GatewayDetectionPlugin)).isNotNull()
    }
}
