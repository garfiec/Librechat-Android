package com.garfiec.librechat.feature.auth.oauth

const val REFRESH_TOKEN_COOKIE = "refreshToken"

fun extractRefreshTokenFromCookies(cookieHeader: String?): String? =
    cookieHeader
        ?.split(";")
        ?.map { it.trim() }
        ?.firstOrNull { it.startsWith("$REFRESH_TOKEN_COOKIE=") }
        ?.substringAfter("$REFRESH_TOKEN_COOKIE=")
        ?.takeIf { it.isNotEmpty() }

fun extractQueryParameter(url: String, name: String): String? {
    val query = url.substringAfter("?", "").substringBefore("#")
    return query
        .split("&")
        .firstOrNull { it.substringBefore("=") == name }
        ?.substringAfter("=", "")
        ?.takeIf { it.isNotEmpty() }
}