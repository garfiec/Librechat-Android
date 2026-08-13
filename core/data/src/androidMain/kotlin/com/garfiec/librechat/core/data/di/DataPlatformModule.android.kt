package com.garfiec.librechat.core.data.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import com.garfiec.librechat.core.common.di.KoinQualifiers
import com.garfiec.librechat.core.data.datastore.CommonTokenDataStore
import com.garfiec.librechat.core.data.datastore.TokenDataStore
import com.garfiec.librechat.core.data.db.LibreChatDatabase
import com.garfiec.librechat.core.data.db.migration.MIGRATION_3_4
import com.garfiec.librechat.core.data.db.migration.MIGRATION_4_5
import com.garfiec.librechat.core.data.prefetch.AttachmentWarmer
import com.garfiec.librechat.core.data.prefetch.CoilAttachmentWarmer
import com.garfiec.librechat.core.data.prefetch.PrefetchScheduler
import com.garfiec.librechat.core.data.prefetch.WorkManagerPrefetchScheduler
import com.garfiec.librechat.core.data.repository.AndroidSwitchCacheCleaner
import com.garfiec.librechat.core.data.repository.CommonSessionCacheCleaner
import com.garfiec.librechat.core.data.repository.SessionCacheCleaner
import com.garfiec.librechat.core.data.repository.SwitchCacheCleaner
import com.garfiec.librechat.core.network.client.SecureTokenStorage
import com.garfiec.librechat.core.network.client.TokenManager
import io.ktor.client.HttpClient
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.binds
import org.koin.dsl.module

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = DATASTORE_FILE_NAME,
    corruptionHandler = settingsCorruptionHandler(),
)

actual val dataPlatformModule: Module = module {

    // Bound per platform rather than defaulted in dataModule: Koin starts with allowOverride(false),
    // so a common default plus a platform override would throw at launch.
    single<AttachmentWarmer> { CoilAttachmentWarmer(androidContext()) }
    single<PrefetchScheduler> { WorkManagerPrefetchScheduler(androidContext()) }

    // --- Database ---
    single {
        Room.databaseBuilder(androidContext(), LibreChatDatabase::class.java, "librechat.db")
            .addMigrations(MIGRATION_3_4, MIGRATION_4_5)
            .build()
    }

    // --- DataStore ---
    single<DataStore<Preferences>> { androidContext().settingsDataStore }

    // --- Token Storage ---
    single {
        TokenDataStore(
            context = androidContext(),
            refreshClient = lazy(LazyThreadSafetyMode.NONE) { get<HttpClient>(KoinQualifiers.Refresh) },
            ioDispatcher = get(KoinQualifiers.IO),
        )
        // Bound as CommonTokenDataStore too: TokenCacheWarmer needs warmTokenCache(), which is
        // deliberately not part of the TokenManager contract.
    } binds arrayOf(TokenManager::class, SecureTokenStorage::class, CommonTokenDataStore::class)

    // --- Session Cache Cleaner ---
    single<SessionCacheCleaner> {
        val context = androidContext()
        CommonSessionCacheCleaner(
            cacheRoot = { context.cacheDir.absolutePath },
        )
    }

    // --- Switch Cache Cleaner (account switch, non-partitionable caches) ---
    single<SwitchCacheCleaner> { AndroidSwitchCacheCleaner() }
}
