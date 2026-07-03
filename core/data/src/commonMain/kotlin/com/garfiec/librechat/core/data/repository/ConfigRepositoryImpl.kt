package com.garfiec.librechat.core.data.repository

import co.touchlab.kermit.Logger
import com.garfiec.librechat.core.common.BackendVersion
import com.garfiec.librechat.core.common.generated.BackendCommitMap
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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException

class ConfigRepositoryImpl(
    private val configApi: ConfigApi,
    private val configCache: ConfigCacheDataStore,
    private val dispatcher: CoroutineDispatcher,
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

    override suspend fun validateServerUrl(url: String): Result<StartupConfig> =
        fetchAndValidateConfig { config ->
            _startupConfig.value = config
            configCache.saveStartupConfig(config)
            logConfigSnapshot(config)
        }

    override suspend fun probeServerUrl(): Result<StartupConfig> =
        // Validation only — publishing/caching would attribute the probed server's config to the
        // live one (see the interface KDoc); the add flow carries the result on its pending session.
        fetchAndValidateConfig { }

    private suspend fun fetchAndValidateConfig(
        onValid: suspend (StartupConfig) -> Unit,
    ): Result<StartupConfig> {
        return try {
            val config = configApi.getStartupConfig()
            if (!isValidLibreChatConfig(config)) {
                Result.Error(message = "This doesn't appear to be a LibreChat server")
            } else {
                onValid(config)
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

    override suspend fun reloadForActiveServer() {
        _startupConfig.value = configCache.loadStartupConfig()
        _endpointConfigs.value = configCache.loadEndpointConfigs().orEmpty()
        _availableModels.value = configCache.loadAvailableModels().orEmpty()
        // Version re-detects from the reloaded config on the next checkBackendVersion pass.
        _detectedBackendVersion.value = null
        loggedConfigSignature = null
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
     * Checks the backend version against the supported version. See [detectVersion] for how the
     * version is resolved (config `version` field, else the build commit).
     *
     * If the version can't be determined, the check passes (fail-open) with
     * [VersionCheckResult.backendVersion] = null and [VersionCheckResult.isCompatible] = true.
     */
    override suspend fun checkBackendVersion(): Result<VersionCheckResult> {
        return safeApiCall {
            // Early UI seed: resolve the version from the persisted config up front so version-gated
            // UI (the drawer's Projects section, pin) is already in place before the user opens the
            // drawer, rather than popping in a beat later once the network fetch below returns.
            // buildInfo.commit lives in the cached config, so this resolves without auth or network.
            // Intentionally does NOT populate _startupConfig — the authoritative pass below still runs
            // a fresh fetch (so a server that changed versions between sessions is detected) and
            // overwrites this; StateFlow conflation means an unchanged version won't re-emit.
            if (_detectedBackendVersion.value == null) {
                configCache.loadStartupConfig()?.let { seed ->
                    detectVersion(seed)?.let { _detectedBackendVersion.value = it }
                }
            }

            // Authoritative pass: fetch a fresh config (fetchStartupConfig falls back to the cache when
            // offline) so a server that changed versions between sessions is picked up, then re-detect
            // and publish. buildInfo.commit is present in every v0.8.7+ config — cached or fresh,
            // authenticated or not — so a single fetch is enough; no auth re-fetch is needed.
            val config = (fetchStartupConfig() as? Result.Success)?.data ?: _startupConfig.value
            val detectedVersion = detectVersion(config)

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
     * Resolves the backend version from a startup config, in order:
     * 1. the explicit `version` field (future-proof — not yet emitted by any release),
     * 2. the build commit (`buildInfo.commit`) looked up in the baked [BackendCommitMap] — the only
     *    reliable server-sent signal (LibreChat has no version endpoint), covering any tagged
     *    official/rc image.
     * Null when neither resolves.
     */
    private suspend fun detectVersion(config: StartupConfig?): String? {
        config?.version?.trimStart('v', 'V')?.takeIf { it.isNotBlank() }?.let { return it }
        val buildInfo = config?.buildInfo
        val commit = buildInfo?.commit
        if (commit != null) {
            // First lookup lazily parses the baked ~1000-entry table; run it (and the O(1) lookups)
            // off the caller thread so the parse never janks the post-auth main-thread moment.
            val resolved = withContext(dispatcher) {
                BackendCommitMap.versionForCommit(commit)
                    ?.let { it to BackendCommitMap.classificationForCommit(commit) }
            }
            if (resolved != null) {
                val (version, classification) = resolved
                Diag.i(
                    "BackendVersion",
                    attrs = mapOf(
                        "resolvedVia" to "buildInfo.commit",
                        "commit" to (buildInfo.commitShort ?: commit),
                        "classification" to (classification ?: "UNKNOWN"),
                        "version" to version,
                    ),
                ) { "backend version resolved from build commit" }
                return version
            }
        }
        return null
    }

    override suspend fun getCategories(): Result<List<Category>> = safeApiCall {
        configApi.getCategories()
    }

    override suspend fun clear() {
        _endpointConfigs.value = emptyMap()
        _availableModels.value = emptyMap()
        _startupConfig.value = null
        _detectedBackendVersion.value = null
        loggedConfigSignature = null
        configCache.clear()
    }
}
