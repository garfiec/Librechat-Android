package com.garfiec.librechat.core.common

/**
 * Backend version compatibility constants and comparison utilities.
 *
 * The LibreChat backend does not currently expose a version endpoint. The version is resolved
 * from `/api/config` — an explicit `version` field if one is ever added, otherwise the build
 * commit (`buildInfo.commit`) looked up in the baked commit→version table. See
 * `ConfigRepositoryImpl.detectVersion`. If the version cannot be determined, the check is
 * silently skipped.
 */
object BackendVersion {

    /**
     * The LibreChat backend version this app was built and tested against.
     * Matches the VERSION constant from the official LibreChat repo's
     * `packages/data-provider/src/config.ts` and `package.json`.
     *
     * Single source of truth: `backendTargetVersion` in the root `version.properties`,
     * code-generated into [BACKEND_TARGET_VERSION] by core/common's `generateBackendVersion`
     * task. Edit the property — not this literal — so the app and CI release notes stay in sync.
     */
    const val SUPPORTED_BACKEND_VERSION = BACKEND_TARGET_VERSION

    /**
     * Represents a parsed semantic version (major.minor.patch).
     */
    data class SemanticVersion(
        val major: Int,
        val minor: Int,
        val patch: Int,
    ) {
        override fun toString(): String = "$major.$minor.$patch"
    }

    /**
     * Parses a version string like "0.8.2", "v0.8.2", "0.8", or "v0.8" into a [SemanticVersion].
     * Returns null if the string cannot be parsed.
     *
     * Semver prerelease and build-metadata suffixes are stripped before parsing: per the
     * spec they begin at the first `-` (prerelease) or `+` (build) following the numeric
     * core, so "0.8.6-rc1" and "0.8.6+build.5" both parse as 0.8.6. Without this, the
     * patch token "6-rc1" failed `toIntOrNull` and silently fell back to 0, downgrading
     * "0.8.6-rc1" to 0.8.0 and defeating every `isCompatibleOrNewer` gate against a
     * prerelease server footer.
     */
    fun parse(version: String): SemanticVersion? {
        val core = version.trim().trimStart('v', 'V').substringBefore('-').substringBefore('+')
        val parts = core.split('.')
        if (parts.size < 2) return null
        val major = parts[0].toIntOrNull() ?: return null
        val minor = parts[1].toIntOrNull() ?: return null
        val patch = if (parts.size >= 3) parts[2].toIntOrNull() ?: 0 else 0
        return SemanticVersion(major, minor, patch)
    }

    /**
     * Checks whether two versions are compatible.
     * Any mismatch in major, minor, or patch is considered incompatible.
     *
     * Patch is included because upstream LibreChat has shipped breaking API
     * changes within the same minor version (e.g. SSE payload shape changes
     * between 0.8.4 and 0.8.5). Treating patch differences as compatible would
     * silently mask those.
     *
     * @return true if the versions match exactly (major, minor, patch), false otherwise.
     *         Returns true if either version cannot be parsed (fail-open).
     */
    fun isCompatible(supported: String, actual: String): Boolean {
        val supportedVersion = parse(supported) ?: return true
        val actualVersion = parse(actual) ?: return true
        return supportedVersion == actualVersion
    }

    /**
     * Checks whether [actual] is greater than or equal to [minimum] (feature-gate check).
     *
     * Use this when branching on whether a backend feature was introduced in a
     * specific version. Comparison is lexicographic over (major, minor, patch) —
     * patch is included because upstream sometimes ships features and breaking
     * changes within a patch release. Returns true when [actual] cannot be parsed
     * (fail-open: assume feature is present). Returns false when [minimum] cannot
     * be parsed (degenerate threshold).
     *
     * **Contract for callers:** null-check or explicitly handle the unknown-version
     * case before invoking. The fail-open default here is intentional because in
     * practice this helper is called only after `ConfigRepositoryImpl.checkBackendVersion()`
     * has persisted either a parsed-valid version string or an explicit `null` to
     * `ConfigRepository.detectedBackendVersion` — garbage never reaches this helper.
     * This is a deliberate divergence from the "default to older-server behavior on
     * unknown version" guideline in `VERSION_GATES.md` §Guidelines #2: that guideline
     * is the callsite rule, and this helper only runs once the callsite has resolved
     * the unknown-version case upstream.
     *
     * @param actual The version detected from the server (e.g., "0.8.5").
     * @param minimum The minimum version at which the gated feature appears (e.g., "0.8.5").
     * @return true if [actual] ≥ [minimum] by (major, minor, patch), false otherwise.
     */
    fun isCompatibleOrNewer(actual: String, minimum: String): Boolean {
        val actualVersion = parse(actual) ?: return true
        val minimumVersion = parse(minimum) ?: return false
        if (actualVersion.major != minimumVersion.major) {
            return actualVersion.major > minimumVersion.major
        }
        if (actualVersion.minor != minimumVersion.minor) {
            return actualVersion.minor > minimumVersion.minor
        }
        return actualVersion.patch >= minimumVersion.patch
    }
}
