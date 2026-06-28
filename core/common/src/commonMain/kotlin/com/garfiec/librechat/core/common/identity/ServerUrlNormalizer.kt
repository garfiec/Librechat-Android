package com.garfiec.librechat.core.common.identity

/**
 * Canonicalizes a server URL into a stable identity key for `serverId = hash(normalizeUrl(url))`.
 *
 * Two URLs that address the same LibreChat deployment must normalize to the same string so a
 * re-added server resolves to the same account bucket; two genuinely different deployments must
 * not collide. The normalization is deliberately conservative:
 *
 * - **Scheme** lowercased; a missing scheme defaults to `https`.
 * - **Host** lowercased (DNS is case-insensitive). IPv6 literals in `[...]` are preserved.
 * - **Default port elided** (`:80` for http, `:443` for https) so `host` and `host:443` agree.
 * - **Trailing slashes stripped**, **query (`?…`) and fragment (`#…`) dropped** — neither is part
 *   of a deployment's identity.
 * - **Path (subpath) RETAINED, case-preserved.** LibreChat is frequently reverse-proxied under a
 *   subpath (`https://host/librechat`), which is a *different* deployment from `https://host/other`
 *   or `https://host`. Path case is server-significant, so it is not folded.
 *
 * This is the single source of identity normalization — callers must not pre-massage the URL
 * (beyond what onboarding already does) lest they reintroduce the trailing-slash/case splits this
 * function exists to collapse.
 */
fun normalizeServerUrl(raw: String): String {
    val trimmed = raw.trim()
    require(trimmed.isNotEmpty()) { "Server URL must not be blank" }

    // Split scheme; default to https when absent (mirrors onboarding's ensureHttps).
    val schemeSep = trimmed.indexOf("://")
    val scheme: String
    val rest: String
    if (schemeSep >= 0) {
        scheme = trimmed.substring(0, schemeSep).lowercase()
        rest = trimmed.substring(schemeSep + 3)
    } else {
        scheme = "https"
        rest = trimmed
    }
    require(scheme == "http" || scheme == "https") { "Unsupported scheme: $scheme" }

    // Strip fragment, then query — neither contributes to deployment identity.
    val noFragment = rest.substringBefore('#')
    val noQuery = noFragment.substringBefore('?')

    // Authority is everything up to the first '/'; the remainder (with the slash) is the path.
    val pathStart = noQuery.indexOf('/')
    val authority = if (pathStart >= 0) noQuery.substring(0, pathStart) else noQuery
    val rawPath = if (pathStart >= 0) noQuery.substring(pathStart) else ""

    val (host, port) = splitHostPort(authority)
    require(host.isNotEmpty()) { "Server URL has no host: $raw" }
    val normalizedHost = host.lowercase()

    val defaultPort = if (scheme == "https") "443" else "80"
    val portSuffix = if (port == null || port == defaultPort) "" else ":$port"

    // Retain the subpath, drop trailing slashes (so `/librechat` and `/librechat/` agree).
    val normalizedPath = rawPath.trimEnd('/')

    return "$scheme://$normalizedHost$portSuffix$normalizedPath"
}

/**
 * Splits an authority into host and optional port, preserving IPv6 bracket literals.
 * Returns host with brackets retained for IPv6 so `[::1]` stays distinguishable from `::1`.
 */
private fun splitHostPort(authority: String): Pair<String, String?> {
    // Drop any userinfo (`user:pass@host`) — credentials aren't part of deployment identity.
    val afterUserInfo = authority.substringAfterLast('@')
    if (afterUserInfo.startsWith('[')) {
        val close = afterUserInfo.indexOf(']')
        if (close >= 0) {
            val host = afterUserInfo.substring(0, close + 1)
            val maybePort = afterUserInfo.substring(close + 1).removePrefix(":").takeIf { it.isNotEmpty() }
            return host to maybePort
        }
    }
    val colon = afterUserInfo.lastIndexOf(':')
    return if (colon >= 0) {
        afterUserInfo.substring(0, colon) to afterUserInfo.substring(colon + 1).takeIf { it.isNotEmpty() }
    } else {
        afterUserInfo to null
    }
}
