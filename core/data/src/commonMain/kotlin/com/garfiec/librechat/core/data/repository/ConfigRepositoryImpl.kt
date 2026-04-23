package com.garfiec.librechat.core.data.repository

import co.touchlab.kermit.Logger
import com.garfiec.librechat.core.common.BackendVersion
import com.garfiec.librechat.core.common.result.ApiException
import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.common.result.safeApiCall
import com.garfiec.librechat.core.data.datastore.ConfigCacheDataStore
import com.garfiec.librechat.core.model.EndpointConfig
import com.garfiec.librechat.core.model.config.StartupConfig
import com.garfiec.librechat.core.network.api.ConfigApi
import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerializationException

class ConfigRepositoryImpl(
    private val configApi: ConfigApi,
    private val configCache: ConfigCacheDataStore,
) : ConfigRepository {

    private val _startupConfig = MutableStateFlow<StartupConfig?>(null)
    override val startupConfig: StateFlow<StartupConfig?> = _startupConfig.asStateFlow()

    private val _endpointConfigs = MutableStateFlow<Map<String, EndpointConfig>>(emptyMap())
    override val endpointConfigs: StateFlow<Map<String, EndpointConfig>> = _endpointConfigs.asStateFlow()

    private val _availableModels = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    override val availableModels: StateFlow<Map<String, List<String>>> = _availableModels.asStateFlow()

    private val _detectedBackendVersion = MutableStateFlow<String?>(null)
    override val detectedBackendVersion: StateFlow<String?> = _detectedBackendVersion.asStateFlow()

    override suspend fun validateServerUrl(url: String): Result<StartupConfig> {
        return try {
            val config = configApi.getStartupConfig()
            if (!isValidLibreChatConfig(config)) {
                Result.Error(message = "This doesn't appear to be a LibreChat server")
            } else {
                _startupConfig.value = config
                configCache.saveStartupConfig(config)
                Result.Success(config)
            }
        } catch (e: SerializationException) {
            Result.Error(e, "This doesn't appear to be a LibreChat server")
        } catch (e: ApiException) {
            val message = when (e.statusCode) {
                404 -> "This doesn't appear to be a LibreChat server"
                in 500..599 -> "Server error. Please try again later."
                else -> e.message ?: "Could not connect to server"
            }
            Result.Error(e, message)
        } catch (e: HttpRequestTimeoutException) {
            Result.Error(e, "Connection timed out. Check the URL and try again.")
        } catch (e: Exception) {
            Result.Error(e, "Could not reach the server. Check the URL and your connection.")
        }
    }

    /**
     * Validates that the config response contains fields specific to LibreChat.
     * `serverDomain` is a required field on LibreChat's /api/config and has
     * defaulted to a non-blank value since v0.7; arbitrary JSON APIs will not
     * populate it.
     */
    private fun isValidLibreChatConfig(config: StartupConfig): Boolean {
        return config.serverDomain.isNotBlank()
    }

    override suspend fun fetchStartupConfig(): Result<StartupConfig> {
        // Emit cached value first if state is empty
        if (_startupConfig.value == null) {
            configCache.loadStartupConfig()?.let { cached ->
                _startupConfig.value = cached
            }
        }

        val result = safeApiCall {
            val config = configApi.getStartupConfig()
            _startupConfig.value = config
            configCache.saveStartupConfig(config)
            config
        }

        // On network failure, return cached data if available
        if (result is Result.Error) {
            val cached = _startupConfig.value
            if (cached != null) {
                Logger.d { "Using cached startup config (network unavailable)" }
                return Result.Success(cached)
            }
        }
        return result
    }

    override suspend fun fetchEndpoints(): Result<Map<String, EndpointConfig>> {
        if (_endpointConfigs.value.isEmpty()) {
            configCache.loadEndpointConfigs()?.let { cached ->
                _endpointConfigs.value = cached
            }
        }

        val result = safeApiCall {
            val endpoints = configApi.getEndpoints()
            _endpointConfigs.value = endpoints
            configCache.saveEndpointConfigs(endpoints)
            endpoints
        }

        if (result is Result.Error) {
            val cached = _endpointConfigs.value
            if (cached.isNotEmpty()) {
                Logger.d { "Using cached endpoint configs (network unavailable)" }
                return Result.Success(cached)
            }
        }
        return result
    }

    override suspend fun fetchModels(): Result<Map<String, List<String>>> {
        if (_availableModels.value.isEmpty()) {
            configCache.loadAvailableModels()?.let { cached ->
                _availableModels.value = cached
            }
        }

        val result = safeApiCall {
            val models = configApi.getModels()
            _availableModels.value = models
            configCache.saveAvailableModels(models)
            models
        }

        if (result is Result.Error) {
            val cached = _availableModels.value
            if (cached.isNotEmpty()) {
                Logger.d { "Using cached models (network unavailable)" }
                return Result.Success(cached)
            }
        }
        return result
    }

    /**
     * Checks the backend version against the supported version.
     *
     * Version detection strategy (in order):
     * 1. The `version` field in the startup config (future-proof: the backend may add this)
     * 2. Parsing the `customFooter` field for a "LibreChat vX.Y.Z" pattern
     *
     * If neither source provides a version, the check passes (fail-open) with
     * [VersionCheckResult.backendVersion] = null and [VersionCheckResult.isCompatible] = true.
     */
    override suspend fun checkBackendVersion(): Result<VersionCheckResult> {
        return safeApiCall {
            // Ensure we have the startup config (use cached if available)
            val config = _startupConfig.value ?: run {
                val fetchResult = fetchStartupConfig()
                (fetchResult as? Result.Success)?.data
            }

            val supported = BackendVersion.SUPPORTED_BACKEND_VERSION

            // Strategy 1: Check for explicit version field
            val detectedVersion = config?.version?.trimStart('v', 'V')
                // Strategy 2: Parse customFooter for version pattern
                ?: BackendVersion.extractVersionFromFooter(config?.customFooter)

            _detectedBackendVersion.value = detectedVersion

            if (detectedVersion != null) {
                Logger.d { "Backend version detected: $detectedVersion (supported: $supported)" }
                VersionCheckResult(
                    backendVersion = detectedVersion,
                    supportedVersion = supported,
                    isCompatible = BackendVersion.isCompatible(supported, detectedVersion),
                )
            } else {
                Logger.d { "Backend version could not be determined, skipping check" }
                VersionCheckResult(
                    backendVersion = null,
                    supportedVersion = supported,
                    isCompatible = true,
                )
            }
        }
    }
}
