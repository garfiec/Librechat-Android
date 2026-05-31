package com.garfiec.librechat.feature.settings.di

import android.app.Application
import android.content.Context
import com.garfiec.librechat.core.common.AppInfo
import com.garfiec.librechat.core.data.datastore.ServerDataStore
import com.garfiec.librechat.core.data.datastore.SettingsDataStore
import com.garfiec.librechat.core.data.datastore.ThemeDataStore
import com.garfiec.librechat.core.data.repository.ApiKeyRepository
import com.garfiec.librechat.core.data.repository.AuthRepository
import com.garfiec.librechat.core.data.repository.BalanceRepository
import com.garfiec.librechat.core.data.repository.ConfigRepository
import com.garfiec.librechat.core.data.repository.ConversationRepository
import com.garfiec.librechat.core.data.repository.FavoritesRepository
import com.garfiec.librechat.core.data.repository.KeyRepository
import com.garfiec.librechat.core.data.repository.McpRepository
import com.garfiec.librechat.core.data.repository.MemoryRepository
import com.garfiec.librechat.core.data.repository.PresetRepository
import com.garfiec.librechat.core.data.repository.RoleRepository
import com.garfiec.librechat.core.data.repository.ShareRepository
import com.garfiec.librechat.core.data.repository.SpeechRepository
import com.garfiec.librechat.core.data.repository.UserRepository
import com.garfiec.librechat.core.data.util.PermissionGate
import com.garfiec.librechat.core.logging.DiagnosticLogRepository
import com.garfiec.librechat.feature.settings.util.ContentReader
import com.garfiec.librechat.feature.settings.util.PlatformCacheCleaner
import com.garfiec.librechat.feature.settings.viewmodel.delegate.SpeechSettingsFactory
import kotlinx.coroutines.CoroutineDispatcher
import org.junit.Test
import org.koin.test.verify.verify

class SettingsModuleVerificationTest {
    @Test
    fun verifySettingsModule() {
        settingsModule.verify(
            extraTypes = listOf(
                Context::class,
                Application::class,
                AppInfo::class,
                UserRepository::class,
                AuthRepository::class,
                ConfigRepository::class,
                ConversationRepository::class,
                FavoritesRepository::class,
                McpRepository::class,
                MemoryRepository::class,
                RoleRepository::class,
                PermissionGate::class,
                SpeechRepository::class,
                BalanceRepository::class,
                ShareRepository::class,
                KeyRepository::class,
                ApiKeyRepository::class,
                PresetRepository::class,
                ThemeDataStore::class,
                ServerDataStore::class,
                SettingsDataStore::class,
                ContentReader::class,
                PlatformCacheCleaner::class,
                SpeechSettingsFactory::class,
                DiagnosticLogRepository::class,
                // Provided by core:common CommonModule via KoinQualifiers.IO.
                CoroutineDispatcher::class,
            ),
        )
    }
}
