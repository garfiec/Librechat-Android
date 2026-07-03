package com.garfiec.librechat.feature.auth.di

import android.app.Application
import android.content.Context
import com.garfiec.librechat.core.data.datastore.ServerDataStore
import com.garfiec.librechat.core.data.repository.AccountSwitcher
import com.garfiec.librechat.core.data.repository.AuthRepository
import com.garfiec.librechat.core.data.repository.ConfigRepository
import com.garfiec.librechat.core.data.repository.UserRepository
import com.garfiec.librechat.core.network.client.SecureTokenStorage
import com.garfiec.librechat.feature.auth.oauth.OAuthLauncher
import org.junit.Test
import org.koin.test.verify.verify

class AuthModuleVerificationTest {
    @Test
    fun verifyAuthModule() {
        authModule.verify(
            extraTypes = listOf(
                Context::class,
                Application::class,
                ServerDataStore::class,
                AuthRepository::class,
                ConfigRepository::class,
                UserRepository::class,
                SecureTokenStorage::class,
                OAuthLauncher::class,
                AccountSwitcher::class,
                // ServerUrlViewModel's addAccount mode flag, injected via parametersOf.
                Boolean::class,
            ),
        )
    }
}
