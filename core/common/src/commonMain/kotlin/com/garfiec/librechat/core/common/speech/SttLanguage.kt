package com.garfiec.librechat.core.common.speech

/**
 * Supported STT dictation languages as a single source of truth shared by the settings language
 * picker (display names) and the platform recognizers (locale tags). Lives in core/common — a
 * pure-Kotlin utilities layer both feature modules depend on — rather than duplicated as a hardcoded
 * picker list in feature/settings and a separate `when` map in feature/chat (which can't depend on
 * each other), so a language can't be offered in the picker while silently mapping to no locale, or
 * vice-versa.
 *
 * Each entry is a display name (stored verbatim in the `stt_language` pref) → BCP-47 locale tag, or
 * `null` for the device-default "Auto-detect".
 */
private val STT_LANGUAGES: List<Pair<String, String?>> = listOf(
    "Auto-detect" to null,
    "English" to "en-US",
    "Spanish" to "es-ES",
    "French" to "fr-FR",
    "German" to "de-DE",
    "Japanese" to "ja-JP",
    "Chinese" to "zh-CN",
)

/** Display names for the STT language picker; first entry is the device-default "Auto-detect". */
val sttLanguageOptions: List<String> = STT_LANGUAGES.map { it.first }

/**
 * Maps a stored STT language name to a BCP-47 locale tag. Returns null for "Auto-detect", empty, and
 * any unmapped name so the recognizer falls back to the device default.
 *
 * Android feeds the tag to `RecognizerIntent.EXTRA_LANGUAGE` (on-device controller + legacy Intent
 * overlay); iOS feeds it to `SFSpeechRecognizer(locale:)`.
 */
fun mapSttLanguageToLocale(language: String): String? =
    STT_LANGUAGES.firstOrNull { it.first.equals(language, ignoreCase = true) }?.second
