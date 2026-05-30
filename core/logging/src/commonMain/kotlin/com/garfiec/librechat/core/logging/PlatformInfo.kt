package com.garfiec.librechat.core.logging

/**
 * Read-only platform/device facts for diagnostic headers and crash records.
 *
 * Deliberately low-cardinality and non-identifying: OS name/version and a coarse device-model
 * string only. Never expose serial numbers, advertising IDs, or anything that could fingerprint
 * an individual install. Provided per-platform via the `loggingPlatformModule` actuals.
 */
interface PlatformInfo {
    /** Operating system family, e.g. `Android` or `iOS`. */
    val osName: String

    /** OS release string, e.g. `14` (Android) or `17.4` (iOS). */
    val osVersion: String

    /** Coarse device model, e.g. `Google Pixel 8` or `iPhone`. */
    val deviceModel: String
}
