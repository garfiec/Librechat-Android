package com.garfiec.librechat.core.data.repository

import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.model.EndpointConfig
import com.garfiec.librechat.core.model.config.StartupConfig
import com.garfiec.librechat.core.model.response.Category
import kotlinx.coroutines.flow.StateFlow

/**
 * Result of a backend version compatibility check.
 */
data class VersionCheckResult(
    /** The version detected on the backend, or null if it could not be determined. */
    val backendVersion: String?,
    /** The version this app was built for. */
    val supportedVersion: String,
    /** Whether the versions are compatible (same major.minor). */
    val isCompatible: Boolean,
)

interface ConfigRepository {
    val startupConfig: StateFlow<StartupConfig?>
    val endpointConfigs: StateFlow<Map<String, EndpointConfig>>
    val availableModels: StateFlow<Map<String, List<String>>>

    /**
     * The backend version detected from `/api/config` (via the `version` field
     * or the build commit). `null` until [checkBackendVersion] runs
     * or if the backend does not expose its version.
     *
     * UI code should consult [com.garfiec.librechat.core.common.BackendVersion.isCompatible]
     * when branching on this value and consult `VERSION_GATES.md` at the repo root
     * when adding new gates.
     */
    val detectedBackendVersion: StateFlow<String?>

    suspend fun validateServerUrl(url: String): Result<StartupConfig>

    /**
     * Validates that the request pipeline's current target is a LibreChat server WITHOUT
     * publishing the fetched config to the in-memory state or the srv:-keyed disk cache.
     * For add-account validation: the call runs under the pending server's request identity
     * while the live account stays active, so [validateServerUrl]'s writes would surface the
     * pending server's config to the live session and poison the live server's cache entry
     * (the cache key derives from the live URL, not the probed one).
     */
    suspend fun probeServerUrl(): Result<StartupConfig>

    /**
     * Replaces the in-memory config state with the active server's cached entries (empty when
     * none), leaving every server's disk cache untouched. Called after an account switch: the
     * in-memory StateFlows are server-blind, so once the URL flips they still hold the outgoing
     * server's config until this reseeds them from the incoming server's srv:-keyed cache.
     */
    suspend fun reloadForActiveServer()

    suspend fun fetchStartupConfig(): Result<StartupConfig>
    suspend fun fetchEndpoints(): Result<Map<String, EndpointConfig>>
    suspend fun fetchModels(): Result<Map<String, List<String>>>

    /**
     * Checks the backend version against this app's supported version.
     * Returns a [VersionCheckResult] on success, or an error if the check fails.
     *
     * The check is best-effort: if the backend does not expose its version,
     * the result will have [VersionCheckResult.backendVersion] = null and
     * [VersionCheckResult.isCompatible] = true (fail-open).
     */
    suspend fun checkBackendVersion(): Result<VersionCheckResult>

    /**
     * Fetches the shared category list (`GET /api/categories`) used by prompts and
     * the skill builder — `{label, value}` pairs (idea, code, write, …). `label` is
     * an i18n key; `value` is what's persisted as the skill/prompt category.
     */
    suspend fun getCategories(): Result<List<Category>>

    /** Clear all cached config state (in-memory + disk). Called on logout. */
    suspend fun clear()
}
