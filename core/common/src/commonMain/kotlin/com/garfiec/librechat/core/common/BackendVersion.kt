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
     *
     * Three legal forms (see version.properties):
     * - `0.8.7` — sync to a stable release tag
     * - `0.8.8-rc1` — sync to a release-candidate tag
     * - `0.8.7+dev.9f8e7d6c` — partial sync to an untagged upstream commit; the base version is
     *   what that commit's package.json reports, the metadata suffix pins the synced commit.
     *   [parse] strips `+…`, so all comparisons key on the base release line.
     */
    const val SUPPORTED_BACKEND_VERSION = BACKEND_TARGET_VERSION

    /**
     * Represents a parsed semantic version (major.minor.patch, optional prerelease).
     *
     * Ordering follows semver: the release triple compares numerically, and a prerelease
     * sorts BELOW its release (`0.8.8-rc1 < 0.8.8`). Prereleases of the same triple compare
     * by label then numeric suffix (`alpha < beta < rc`, `rc1 < rc2`). Build metadata (`+…`)
     * never reaches here — [parse] strips it.
     *
     * CAUTION: data-class `equals` compares [preRelease] as a raw string, so `compareTo` is
     * NOT consistent with `equals` for spelling variants ("rc1" vs "rc.1" compare equal but
     * are not `equals`), and the constructor bypasses [parse]'s lowercasing. Compare versions
     * with the comparison operators / [BackendVersion] helpers, not `==`, and don't use this
     * type as a map/set key across differently-spelled sources.
     */
    data class SemanticVersion(
        val major: Int,
        val minor: Int,
        val patch: Int,
        val preRelease: String? = null,
    ) : Comparable<SemanticVersion> {

        override fun compareTo(other: SemanticVersion): Int {
            if (major != other.major) return major.compareTo(other.major)
            if (minor != other.minor) return minor.compareTo(other.minor)
            if (patch != other.patch) return patch.compareTo(other.patch)
            val mine = preRelease
            val theirs = other.preRelease
            return when {
                mine == null && theirs == null -> 0
                mine == null -> 1 // release > any prerelease of the same triple
                theirs == null -> -1
                else -> comparePreRelease(mine, theirs)
            }
        }

        override fun toString(): String =
            "$major.$minor.$patch" + (preRelease?.let { "-$it" } ?: "")

        private fun comparePreRelease(a: String, b: String): Int {
            val (labelA, numA) = splitPreRelease(a)
            val (labelB, numB) = splitPreRelease(b)
            if (labelA != labelB) return labelA.compareTo(labelB) // alpha < beta < rc, luckily alphabetical
            return numA.compareTo(numB)
        }

        // "rc1" / "rc.1" / "rc-1" → ("rc", 1); bare "rc" → ("rc", 0). Separators are ignored so
        // upstream's occasional "-rc.1" spelling orders identically to "-rc1".
        private fun splitPreRelease(raw: String): Pair<String, Int> {
            val label = raw.takeWhile { it.isLetter() }
            val number = raw.dropWhile { !it.isDigit() }.takeWhile { it.isDigit() }.toIntOrNull() ?: 0
            return label to number
        }
    }

    /**
     * Parses a version string like "0.8.2", "v0.8.2", "0.8", or "0.8.8-rc1" into a
     * [SemanticVersion]. Returns null if the string cannot be parsed.
     *
     * The semver prerelease suffix (first `-` after the numeric core) is RETAINED (lowercased)
     * so rc-level gates can order correctly; build metadata (first `+`) is stripped, so a
     * partial-sync target like "0.8.7+dev.9f8e7d6c" parses as its base release line 0.8.7.
     * (Historical note: before prerelease handling, the patch token "6-rc1" failed
     * `toIntOrNull` and silently fell back to 0, downgrading "0.8.6-rc1" to 0.8.0 and
     * defeating every gate against a prerelease server footer.)
     */
    fun parse(version: String): SemanticVersion? {
        val noMetadata = version.trim().trimStart('v', 'V').substringBefore('+')
        val core = noMetadata.substringBefore('-')
        val preRelease = noMetadata.substringAfter('-', missingDelimiterValue = "")
            .lowercase().takeIf { it.isNotEmpty() }
        val parts = core.split('.')
        if (parts.size < 2) return null
        val major = parts[0].toIntOrNull() ?: return null
        val minor = parts[1].toIntOrNull() ?: return null
        val patch = if (parts.size >= 3) parts[2].toIntOrNull() ?: 0 else 0
        return SemanticVersion(major, minor, patch, preRelease)
    }

    /**
     * Checks whether two versions are compatible — i.e. on the same release line.
     * Any mismatch in major, minor, or patch is considered incompatible; the prerelease
     * suffix is deliberately IGNORED, so "0.8.8-rc1", "0.8.8-rc2", and "0.8.8" all count
     * as compatible with each other. This feeds the soft version-mismatch banner, where
     * flagging an rc of the targeted line as "incompatible" would be noise.
     *
     * Patch is included because upstream LibreChat has shipped breaking API
     * changes within the same minor version (e.g. SSE payload shape changes
     * between 0.8.4 and 0.8.5). Treating patch differences as compatible would
     * silently mask those.
     *
     * @return true if the versions match on (major, minor, patch), false otherwise.
     *         Returns true if either version cannot be parsed (fail-open).
     */
    fun isCompatible(supported: String, actual: String): Boolean {
        val supportedVersion = parse(supported) ?: return true
        val actualVersion = parse(actual) ?: return true
        return supportedVersion.copy(preRelease = null) == actualVersion.copy(preRelease = null)
    }

    /**
     * Checks whether [actual] is greater than or equal to [minimum] (feature-gate check).
     *
     * Use this when branching on whether a backend feature was introduced in a
     * specific version. Comparison is full semver order over
     * (major, minor, patch, prerelease) — patch is included because upstream sometimes
     * ships features and breaking changes within a patch release, and prerelease is
     * ordered (`0.8.8-rc1 < 0.8.8-rc2 < 0.8.8`) so gates can be declared at rc
     * granularity. Declare each gate at the FIRST version that carries the feature:
     * a feature present in rc1 gates on "0.8.8-rc1" (a "0.8.8" gate would exclude rc
     * servers); a feature added between rc1 and final gates on the rc that first has it.
     * Returns true when [actual] cannot be parsed (fail-open: assume feature is
     * present). Returns false when [minimum] cannot be parsed (degenerate threshold).
     *
     * For servers running UNTAGGED upstream commits (whose package.json still reports the
     * previous release), version comparison alone under-reports — use [supportsFeature]
     * with a landed date instead.
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
     * @param actual The version detected from the server (e.g., "0.8.5", "0.8.8-rc1").
     * @param minimum The minimum version at which the gated feature appears (e.g., "0.8.8-rc1").
     * @return true if [actual] ≥ [minimum] by semver order, false otherwise.
     */
    fun isCompatibleOrNewer(actual: String, minimum: String): Boolean {
        val actualVersion = parse(actual) ?: return true
        val minimumVersion = parse(minimum) ?: return false
        return actualVersion >= minimumVersion
    }

    /**
     * Feature-gate check that also understands servers built from UNTAGGED upstream commits.
     *
     * Upstream only bumps package.json at rc prep, so a server running a dev commit with
     * next-release features still REPORTS the previous release version (e.g. a 0.8.8-cycle
     * dev build reports "0.8.7") — [isCompatibleOrNewer] alone would hide the feature. This
     * helper first applies the version gate, then falls back to the build commit's DATE for
     * DEV-classified servers: if the server's build commit is at or after the day the feature
     * landed upstream, the feature is present. Dates are ISO `yyyy-MM-dd` in UTC — derive
     * landedDate with `TZ=UTC git log -1 --date=format-local:%Y-%m-%d --format=%cd <commit>`,
     * matching how the commit map bakes dates — and compare lexicographically. Upstream
     * squash-merges stamp commit times at merge, so in a single timezone the dates are
     * monotonic (verified over the map window; per-committer-timezone `%cs` dates are NOT
     * monotonic and must not be used for landedDate).
     *
     * GRANULARITY CAVEAT: the comparison is by DAY, so every commit sharing the landing commit's
     * date satisfies the gate — including the ones that merged hours BEFORE it. Upstream lands a
     * dozen-plus commits a day, so this is routine, not a corner case. Pick landedDate for the
     * direction whose misclassification is harmless: use the landing day when a same-day
     * predecessor being treated as "has the feature" is tolerable, and the day AFTER when it is
     * not (then same-day successors are treated as "lacks the feature" instead). A landedDate that
     * needs neither error is not expressible without a per-commit ordinal in the commit map.
     *
     * Coverage window: the commit map knows tags plus dev commits only UP TO the app's pinned
     * upstream commit. A server built from a LATER commit resolves to no version at all
     * (null [detected]), so this helper fails closed — a server tracking upstream `latest`
     * drifts past the pin within days of a sync, hiding date-gated features again until the
     * next sync/regeneration. Accepted fail-safe tradeoff; documented in VERSION_GATES.md.
     *
     * Fail-closed on null [detected]: an unresolved server hides date-gated features, matching
     * the VERSION_GATES.md v0.8.7 precedent (surfacing an action that 404s is worse than
     * hiding it). Callsites wanting fail-open must handle null themselves before calling.
     *
     * @param detected The resolved server identity, or null when detection failed.
     * @param minVersion First TAGGED version carrying the feature (e.g. "0.8.8-rc1" — use the
     *        upcoming rc line even before the tag exists).
     * @param landedDate ISO date the feature's upstream commit landed (e.g. "2026-07-14"), or
     *        null to gate on version only.
     */
    fun supportsFeature(
        detected: DetectedBackend?,
        minVersion: String,
        landedDate: String? = null,
    ): Boolean {
        if (detected == null) return false
        if (isCompatibleOrNewer(detected.version, minVersion)) return true
        if (landedDate != null &&
            detected.classification == BackendBuildClass.DEV &&
            detected.commitDate != null
        ) {
            return detected.commitDate >= landedDate
        }
        return false
    }
}
