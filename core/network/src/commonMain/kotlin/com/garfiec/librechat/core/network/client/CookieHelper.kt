package com.garfiec.librechat.core.network.client

import io.ktor.http.Headers

object CookieHelper {

    fun extractRefreshToken(headers: Headers): String? {
        val cookies = headers.getAll("Set-Cookie") ?: return null
        for (cookie in cookies) {
            val parts = cookie.split(";").map { it.trim() }
            val nameValue = parts.firstOrNull() ?: continue
            if (nameValue.startsWith("refreshToken=")) {
                return nameValue.removePrefix("refreshToken=")
            }
        }
        return null
    }
}
