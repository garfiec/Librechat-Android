package com.garfiec.librechat.core.common.extensions

fun String.trimTrailingSlash(): String = trimEnd('/')

/**
 * The host portion of a base URL, for a display label (switcher chip / roster row): the text
 * between `://` and the first `/`, falling back to the whole string when there is no host to strip.
 */
fun String.serverHostLabel(): String = substringAfter("://").substringBefore('/').ifBlank { this }
