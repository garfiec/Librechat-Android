package com.garfiec.librechat.core.common.extensions

fun String.trimTrailingSlash(): String = trimEnd('/')

fun String.isValidUrl(): Boolean =
    startsWith("http://") || startsWith("https://")

/**
 * The host portion of a base URL, for a display label (switcher chip / roster row): the text
 * between `://` and the first `/`, falling back to the whole string when there is no host to strip.
 */
fun String.serverHostLabel(): String = substringAfter("://").substringBefore('/').ifBlank { this }

fun String.ensureHttps(): String = when {
    startsWith("https://") -> this
    startsWith("http://") -> replaceFirst("http://", "https://")
    else -> "https://$this"
}

fun String.truncate(maxLength: Int, ellipsis: String = "..."): String =
    if (length <= maxLength) {
        this
    } else {
        take(maxLength - ellipsis.length) + ellipsis
    }
