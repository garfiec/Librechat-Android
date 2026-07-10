package com.garfiec.librechat.core.model.speech

/**
 * Speech-to-text engine choice, aligned with the web app: [BROWSER] (the platform's native
 * on-device / cloud recognizer) vs [EXTERNAL] (server-side transcription, e.g. Whisper).
 *
 * Persisted lower-case in `KEY_STT_ENGINE` to match the web client, where `engineSTT`
 * defaults to `'browser'` (`client/src/store/settings.ts`). Reads are lenient for
 * back-compat with the pre-parity mobile values (no migration write): `"external"`/`"whisper"`
 * map to [EXTERNAL]; everything else (`""`, `"default"`, `"google"`, `"device"`) maps to
 * [BROWSER], which matches the web default for a blank value.
 */
enum class SttEngine(val storedValue: String) {
    BROWSER("browser"),
    EXTERNAL("external"),
    ;

    companion object {
        fun fromStored(value: String?): SttEngine = when (value?.trim()?.lowercase()) {
            "external", "whisper" -> EXTERNAL
            else -> BROWSER
        }
    }
}
