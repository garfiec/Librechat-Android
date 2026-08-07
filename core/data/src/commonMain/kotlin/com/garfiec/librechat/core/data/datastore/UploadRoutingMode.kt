package com.garfiec.librechat.core.data.datastore

/**
 * How a composer attachment picks between being sent to the provider natively and being extracted
 * to text server-side.
 *
 * - [AUTO] — the app decides per file, from the MIME type and the provider behind the current
 *   selection. The default, and the only mode that needs no understanding of the distinction.
 * - [MANUAL] — the app still decides, but asks first, for any file where both modes are genuinely
 *   available.
 *
 * There is deliberately no "always send to the provider" value: automatic routing only diverges
 * from the provider path where the provider demonstrably cannot take the file, so such a setting
 * could only ever pin a known-broken outcome.
 */
enum class UploadRoutingMode {
    AUTO, MANUAL;

    companion object {
        fun fromString(value: String?): UploadRoutingMode = when (value) {
            "manual" -> MANUAL
            // Unset / unrecognized → automatic, which asks the user nothing.
            else -> AUTO
        }
    }

    fun toStorageString(): String = when (this) {
        AUTO -> "auto"
        MANUAL -> "manual"
    }
}
