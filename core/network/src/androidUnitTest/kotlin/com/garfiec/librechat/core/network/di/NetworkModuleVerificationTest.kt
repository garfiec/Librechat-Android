package com.garfiec.librechat.core.network.di

import android.app.Application
import android.content.Context
import com.garfiec.librechat.core.common.identity.ActiveAccountProvider
import com.garfiec.librechat.core.network.client.AccountReadyGate
import com.garfiec.librechat.core.network.client.ServerUrlProvider
import com.garfiec.librechat.core.network.client.TokenManager
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineFactory
import org.junit.Test
import org.koin.test.verify.verify

class NetworkModuleVerificationTest {
    @Test
    fun verifyNetworkModule() {
        networkModule.verify(
            extraTypes = listOf(
                Context::class,
                Application::class,
                HttpClientEngine::class,
                HttpClientEngineFactory::class,
                TokenManager::class,
                ServerUrlProvider::class,
                // SwitchGate consumes the active-account signal (bound in :core:data) + the account
                // ready gate (getOrNull) — cross-module, so whitelist them for the isolated verify.
                ActiveAccountProvider::class,
                AccountReadyGate::class,
            ),
        )
    }
}
