package com.garfiec.librechat.shared

import com.garfiec.librechat.core.data.datastore.ServerDataStore
import com.garfiec.librechat.core.data.datastore.SettingsDataStore
import com.garfiec.librechat.core.data.repository.AuthRepository
import com.garfiec.librechat.core.data.repository.ConfigRepository
import com.garfiec.librechat.core.data.repository.FileRepository
import com.garfiec.librechat.core.model.ModelRef
import com.garfiec.librechat.feature.chat.navigation.ModelShortcutBus
import kotlinx.coroutines.flow.first
import org.koin.core.Koin

/**
 * Swift-callable accessors for Koin-managed singletons.
 * The Koin instance is stored here by startIosKoin() and used
 * by Swift via typed helper functions.
 *
 * All functions use @Throws to ensure Kotlin exceptions propagate
 * as NSError to Swift rather than causing SIGABRT via
 * trapOnUndeclaredException.
 */
object IosKoinAccessor {

    internal lateinit var koin: Koin

    @Throws(Exception::class)
    fun getSDK(): LibreChatSDK = koin.get()

    @Throws(Exception::class)
    fun getServerDataStore(): ServerDataStore = koin.get()

    @Throws(Exception::class)
    fun getAuthRepository(): AuthRepository = koin.get()

    @Throws(Exception::class)
    fun getFileRepository(): FileRepository = koin.get()

    @Throws(Exception::class)
    fun getConfigRepository(): ConfigRepository = koin.get()

    /**
     * Snapshot of the account's most-used models, for the Swift layer to publish as home-screen
     * quick actions (typically read when the app backgrounds). Suspends until the account resolves
     * (the flow emits nothing while warming), which is already the case by background time.
     */
    @Throws(Exception::class)
    suspend fun currentTopModels(limit: Int): List<ModelRef> =
        koin.get<SettingsDataStore>().topUsedModels(limit).first()

    /** Routes a tapped quick action into the shared navigation host — opens a new chat on the model. */
    @Throws(Exception::class)
    fun requestModelShortcut(endpoint: String, model: String) {
        koin.get<ModelShortcutBus>().request(endpoint, model)
    }
}
