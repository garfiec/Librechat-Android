package com.garfiec.librechat.core.data.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import co.touchlab.kermit.Logger
import com.garfiec.librechat.core.common.identity.deriveServerId
import com.garfiec.librechat.core.model.EndpointConfig
import com.garfiec.librechat.core.model.config.StartupConfig
import com.garfiec.librechat.core.network.client.ServerUrlProvider
import kotlinx.coroutines.flow.first
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Server-scoped config cache. Startup config / endpoint configs / available models are cached under
 * `srv:<serverId>:<name>` so switching servers (multi-server, issue #179) never surfaces another
 * deployment's endpoints or models. `serverId` is derived from the warmed base URL — config is only
 * ever read/written after a server is selected, so the URL is available.
 */
class ConfigCacheDataStore(
    private val dataStore: DataStore<Preferences>,
    private val json: Json,
    private val serverUrlProvider: ServerUrlProvider,
) {

    suspend fun saveStartupConfig(config: StartupConfig) =
        save(STARTUP_CONFIG, "startup config") {
            json.encodeToString(StartupConfig.serializer(), config)
        }

    suspend fun loadStartupConfig(): StartupConfig? =
        load(STARTUP_CONFIG, "startup config") { json.decodeFromString(StartupConfig.serializer(), it) }

    suspend fun saveEndpointConfigs(endpoints: Map<String, EndpointConfig>) =
        save(ENDPOINT_CONFIGS, "endpoint configs") { json.encodeToString(endpointSerializer, endpoints) }

    suspend fun loadEndpointConfigs(): Map<String, EndpointConfig>? =
        load(ENDPOINT_CONFIGS, "endpoint configs") { json.decodeFromString(endpointSerializer, it) }

    suspend fun saveAvailableModels(models: Map<String, List<String>>) =
        save(AVAILABLE_MODELS, "available models") { json.encodeToString(modelsSerializer, models) }

    suspend fun loadAvailableModels(): Map<String, List<String>>? =
        load(AVAILABLE_MODELS, "available models") { json.decodeFromString(modelsSerializer, it) }

    suspend fun clear() {
        try {
            // Scope to the logged-out server only: logout keeps the base URL, so serverId() still
            // resolves to it. Other retained accounts may sit on different servers whose cached
            // config must survive — a blanket wipe across all servers would strip a retained
            // account's endpoints/models. Legacy bare (pre-keying) entries are dropped
            // unconditionally.
            val serverId = serverId()
            dataStore.edit { prefs ->
                serverId?.let { id -> BASES.forEach { prefs.remove(key(id, it)) } }
                BASES.forEach { prefs.remove(stringPreferencesKey(it)) }
            }
        } catch (e: Exception) {
            Logger.w(e) { "Failed to clear cached config" }
        }
    }

    private suspend fun save(base: String, label: String, serialize: () -> String) {
        val serverId = serverId() ?: return
        try {
            val serialized = serialize()
            dataStore.edit { prefs ->
                prefs[key(serverId, base)] = serialized
                prefs.remove(stringPreferencesKey(base)) // drop the pre-keying bare entry once
            }
        } catch (e: Exception) {
            Logger.w(e) { "Failed to cache $label" }
        }
    }

    private suspend fun <T> load(base: String, label: String, deserialize: (String) -> T): T? {
        val serverId = serverId() ?: return null
        return try {
            dataStore.data.first()[key(serverId, base)]?.let(deserialize)
        } catch (e: Exception) {
            Logger.w(e) { "Failed to load cached $label" }
            null
        }
    }

    private suspend fun serverId(): String? =
        serverUrlProvider.awaitBaseUrl().takeIf { it.isNotBlank() }?.let { deriveServerId(it).value }

    private fun key(serverId: String, base: String) = serverScopedKey(serverId, base)

    private companion object {
        const val STARTUP_CONFIG = "cached_startup_config"
        const val ENDPOINT_CONFIGS = "cached_endpoint_configs"
        const val AVAILABLE_MODELS = "cached_available_models"
        val BASES = listOf(STARTUP_CONFIG, ENDPOINT_CONFIGS, AVAILABLE_MODELS)

        val endpointSerializer = MapSerializer(String.serializer(), EndpointConfig.serializer())
        val modelsSerializer = MapSerializer(String.serializer(), ListSerializer(String.serializer()))
    }
}
