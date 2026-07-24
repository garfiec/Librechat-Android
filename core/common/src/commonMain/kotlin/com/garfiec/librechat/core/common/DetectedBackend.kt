package com.garfiec.librechat.core.common

/**
 * How the detected backend build relates to upstream's release process.
 * Derived from `BackendCommitMap.classificationForCommit`.
 */
enum class BackendBuildClass {
    /** Build commit is an official release tag. */
    OFFICIAL,

    /** Build commit is a prerelease tag (rc/beta/alpha). */
    RC,

    /** Untagged commit from the upstream dev history — package.json still reports the previous release. */
    DEV,

    /** Version came from an explicit config `version` field or the commit wasn't classifiable. */
    UNKNOWN,
}

/**
 * The resolved identity of the connected backend, published by
 * `ConfigRepository.detectedBackend` once `checkBackendVersion()` has run.
 *
 * [version] is the version the server reports (via the commit map or an explicit config
 * field), e.g. "0.8.7" or "0.8.8-rc1". For [BackendBuildClass.DEV] builds this UNDERSTATES
 * the server: upstream bumps package.json only at rc prep, so a dev build carrying
 * next-release features still reports the previous release. [commitDate] (ISO `yyyy-MM-dd`,
 * committer date of the build commit) disambiguates — see `BackendVersion.supportsFeature`.
 */
data class DetectedBackend(
    val version: String,
    val classification: BackendBuildClass = BackendBuildClass.UNKNOWN,
    val commitDate: String? = null,
)
