package com.librechat.android.core.model.speech

import java.util.Locale

/**
 * Maps the STT language string stored in settings (see speech settings UI) to an ISO-639-1 code
 * for the LibreChat `/speech/stt` `language` form field, which Whisper uses to constrain recognition.
 */
private fun sttLanguageSettingsToIso6391(language: String): String? = when (language.lowercase()) {
    "english" -> "en"
    "spanish" -> "es"
    "french" -> "fr"
    "german" -> "de"
    "japanese" -> "ja"
    "chinese" -> "zh"
    "auto-detect", "" -> null
    else -> null
}

/**
 * Resolves which language code to send to server STT (Whisper).
 *
 * When the user picks a specific language in settings, that wins. When the setting is blank or
 * "auto-detect", we fall back to the device UI [Locale] so the model is not left in full
 * auto-detect mode (which often mislabels similar Romance languages, etc.).
 */
fun resolveWhisperLanguageCode(settingsSttLanguage: String): String? {
    sttLanguageSettingsToIso6391(settingsSttLanguage)?.let { return it }
    if (settingsSttLanguage.isBlank() || settingsSttLanguage.equals("auto-detect", ignoreCase = true)) {
        val lang = Locale.getDefault().language
        return if (lang.length == 2) lang.lowercase(Locale.ROOT) else null
    }
    return null
}
