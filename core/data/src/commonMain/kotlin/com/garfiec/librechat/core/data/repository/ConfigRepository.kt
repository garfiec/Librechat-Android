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
     * or the `customFooter` pattern). `null` until [checkBackendVersion] runs
     * or if the backend does not expose its version.
     *
     * UI code should consult [com.garfiec.librechat.core.common.BackendVersion.isCompatible]
     * when branching on this value and consult `VERSION_GATES.md` at the repo root
     * when adding new gates.
     */
    val detectedBackendVersion: StateFlow<String?>

    suspend fun validateServerUrl(url: String): Result<StartupConfig>
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
