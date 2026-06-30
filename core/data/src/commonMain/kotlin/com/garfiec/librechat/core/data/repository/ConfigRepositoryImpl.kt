package com.garfiec.librechat.core.data.repository

import co.touchlab.kermit.Logger
import com.garfiec.librechat.core.common.BackendVersion
import com.garfiec.librechat.core.common.result.ApiException
import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.common.result.safeApiCall
import com.garfiec.librechat.core.data.datastore.ConfigCacheDataStore
import com.garfiec.librechat.core.logging.Diag
import com.garfiec.librechat.core.model.EndpointConfig
import com.garfiec.librechat.core.model.config.StartupConfig
import com.garfiec.librechat.core.model.response.Category
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

    /**
     * Identity of the last config we logged a snapshot for, so we emit one snapshot
     * per fetch/change rather than on every recomposition or duplicate fetch.
     */
    private var loggedConfigSignature: Int? = null

    /**
     * Whether the post-auth re-fetch in [checkBackendVersion] has already run this session. The cached
     * onboarding config is unauthenticated and v0.8.7+ gates `customFooter` behind auth, so we force one
     * authenticated re-fetch — but only once, so a server that genuinely never exposes a version doesn't
     * re-fetch on every [checkBackendVersion] call. Reset by [clear] on logout / server switch.
     */
    private var versionRefetchAttempted: Boolean = false

    /**
     * Emits a single redacted, low-cardinality snapshot of the server config to the
     * diagnostic log on first fetch and whenever the relevant feature flags change.
     * NEVER includes serverDomain, URLs, secrets (turnstile/balance), or analyticsGtmId.
     */
    private fun logConfigSnapshot(config: StartupConfig) {
        // Fold the detected backend version and endpoint count into the signature so a snapshot
        // re-emits once the version is detected (it's resolved after the first config fetch) or the
        // endpoint set changes — otherwise the export would forever show detectedBackendVersion=unknown.
        var signature = config.hashCode()
        signature = 31 * signature + (_detectedBackendVersion.value?.hashCode() ?: 0)
        signature = 31 * signature + _endpointConfigs.value.size
        if (signature == loggedConfigSignature) return
        loggedConfigSignature = signature

        Diag.i(
            "ServerConfig",
            attrs = mapOf(
                "registrationEnabled" to config.registrationEnabled.toString(),
                "emailLoginEnabled" to config.emailLoginEnabled.toString(),
                "socialLoginEnabled" to config.socialLoginEnabled.toString(),
                "passwordResetEnabled" to config.passwordResetEnabled.toString(),
                "sharedLinksEnabled" to config.sharedLinksEnabled.toString(),
                "webSearch" to (config.webSearch != null).toString(),
                "modelSpecs" to (config.modelSpecs != null).toString(),
                "endpointCount" to _endpointConfigs.value.size.toString(),
                "detectedBackendVersion" to (_detectedBackendVersion.value ?: "unknown"),
                "supportedBackendVersion" to BackendVersion.SUPPORTED_BACKEND_VERSION,
            ),
        ) { "server config snapshot" }
    }

    override suspend fun validateServerUrl(url: String): Result<StartupConfig> {
        return try {
            val config = configApi.getStartupConfig()
            if (!isValidLibreChatConfig(config)) {
                Result.Error(message = "This doesn't appear to be a LibreChat server")
            } else {
                _startupConfig.value = config
                configCache.saveStartupConfig(config)
                logConfigSnapshot(config)
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
            logConfigSnapshot(config)
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
            // Use the cached startup config if present, else fetch it. A cached config can be a
            // pre-login (unauthenticated) onboarding snapshot, and v0.8.7+ only exposes the version
            // source (`customFooter`) to authenticated requests — so a cached pre-login config yields
            // no version, leaving every version-gated feature (pinned, projects) disabled for the whole
            // first session. If detection comes up empty against a cached config, force one fresh
            // (now-authenticated) fetch and retry before concluding "unknown" — at most once per session
            // (see [versionRefetchAttempted]) so version-less servers don't re-fetch on every call.
            val cached = _startupConfig.value
            var config = cached ?: (fetchStartupConfig() as? Result.Success)?.data
            var detectedVersion = detectVersion(config)
            if (detectedVersion == null && cached != null && !versionRefetchAttempted) {
                // Consume the one-shot only on a SUCCESSFUL re-fetch: a transient failure (network
                // blip right after login) must not burn the single retry and strand every v0.8.7
                // feature disabled for the whole session. A genuinely version-less server still
                // re-fetches exactly once (the fetch succeeds, the version is just absent).
                val refetched = (fetchStartupConfig() as? Result.Success)?.data
                if (refetched != null) {
                    versionRefetchAttempted = true
                    config = refetched
                    detectedVersion = detectVersion(config)
                }
            }

            val supported = BackendVersion.SUPPORTED_BACKEND_VERSION

            _detectedBackendVersion.value = detectedVersion

            // Re-emit the config snapshot now that the backend version is resolved, so the export's
            // version-mismatch signal is accurate (the first snapshot ran before detection).
            config?.let { logConfigSnapshot(it) }

            if (detectedVersion != null) {
                val compatible = BackendVersion.isCompatible(supported, detectedVersion)
                // Dedicated structured record emitted the moment the version is resolved, carrying the
                // compatibility verdict — greppable by tag for triage (versions are server software
                // facts, not PII). The holistic ServerConfig snapshot above also includes the version.
                Diag.i(
                    "BackendVersion",
                    attrs = mapOf(
                        "detectedBackendVersion" to detectedVersion,
                        "supportedBackendVersion" to supported,
                        "compatible" to compatible.toString(),
                    ),
                ) { "backend version detected" }
                VersionCheckResult(
                    backendVersion = detectedVersion,
                    supportedVersion = supported,
                    isCompatible = compatible,
                )
            } else {
                Diag.i(
                    "BackendVersion",
                    attrs = mapOf(
                        "detectedBackendVersion" to "unknown",
                        "supportedBackendVersion" to supported,
                        "compatible" to "true",
                    ),
                ) { "backend version could not be determined" }
                VersionCheckResult(
                    backendVersion = null,
                    supportedVersion = supported,
                    isCompatible = true,
                )
            }
        }
    }

    /**
     * Resolves the backend version from a startup config: the explicit `version` field first
     * (future-proof), then the `customFooter` "LibreChat vX.Y.Z" pattern. Null when neither is present.
     */
    private fun detectVersion(config: StartupConfig?): String? =
        config?.version?.trimStart('v', 'V')
            ?: BackendVersion.extractVersionFromFooter(config?.customFooter)

    override suspend fun getCategories(): Result<List<Category>> = safeApiCall {
        configApi.getCategories()
    }

    override suspend fun clear() {
        _endpointConfigs.value = emptyMap()
        _availableModels.value = emptyMap()
        _startupConfig.value = null
        _detectedBackendVersion.value = null
        loggedConfigSignature = null
        versionRefetchAttempted = false
        configCache.clear()
    }
}
