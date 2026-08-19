package com.garfiec.librechat.feature.agents.util

import kotlin.math.absoluteValue

/**
 * MIRRORED SERVER LOGIC — registered in `scripts/mirrors.json` as `mcp-normalize-server-name`.
 * Verbatim port of `normalizeServerName` in `packages/data-provider/src/config.ts`.
 *
 * The MCP tool cache and the registry inspector build their tool keys as
 * `"<toolName>_mcp_<normalizeServerName(server)>"`, but `GET /api/mcp/servers` still reports the
 * **raw** configured name. So a server configured as `Google Workspace` is advertised under that
 * name and keyed under `Google_Workspace`, and a client that writes the raw name into an agent's
 * tools list produces a key no producer honours — upstream reports it as "Tool not found" at
 * execution time, with every per-tool option (`defer_loading`, `allowed_callers`,
 * `run_in_background`, `describe_intent`) silently inert.
 *
 * Normalizing is correct against older servers too: any name that already consists of safe
 * characters is returned unchanged, which is every name that ever worked.
 *
 * The rule, in upstream's order — do not "simplify" it, each step is load-bearing:
 * 1. a name that is entirely `[a-zA-Z0-9_.-]` is returned as-is;
 * 2. otherwise every other character becomes `_`, then leading and trailing `_` are trimmed;
 * 3. if that leaves nothing (a name of only emoji, say), fall back to `server_<hash>` using
 *    JavaScript's `hash = (hash << 5) - hash + charCode` folded to a signed 32-bit int.
 */
fun normalizeMcpServerName(serverName: String): String {
    if (serverName.isNotEmpty() && serverName.all { it.isSafeServerNameChar() }) return serverName

    val normalized = serverName
        .map { if (it.isSafeServerNameChar()) it else '_' }
        .joinToString("")
        .trim('_')
    if (normalized.isNotEmpty()) return normalized

    // `hash |= 0` in JS truncates to a signed 32-bit int, which Kotlin's Int does inherently;
    // `charCodeAt` is a UTF-16 code unit, which is exactly what iterating a Kotlin Char gives.
    var hash = 0
    for (char in serverName) {
        hash = (hash shl 5) - hash + char.code
    }
    // Widened to Long before the absolute value ON PURPOSE. `Math.abs` in JS operates on a double,
    // so `Math.abs(-2147483648)` is 2147483648 — but `Int.MIN_VALUE.absoluteValue` in Kotlin is
    // still `Int.MIN_VALUE`, and the two sides would then disagree about the key for exactly the
    // input that overflows. One name in four billion, and unreproducible when it happens.
    return "server_${hash.toLong().absoluteValue}"
}

/**
 * Recovers the raw configured server name behind a possibly-normalized one.
 *
 * The read side needs this because an agent's stored tool key holds the normalized name while
 * every display and match surface — the MCP servers list, the marketplace rows — speaks the raw
 * one. Returns [candidate] unchanged when nothing in [knownRawNames] normalizes to it, so a name
 * this client has no server list for still round-trips.
 */
fun resolveRawMcpServerName(candidate: String, knownRawNames: Collection<String>): String =
    knownRawNames.firstOrNull { it == candidate }
        ?: knownRawNames.firstOrNull { normalizeMcpServerName(it) == candidate }
        ?: candidate

/** The `[a-zA-Z0-9_.-]` class upstream's regex uses, spelled out so it needs no Regex. */
private fun Char.isSafeServerNameChar(): Boolean =
    this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9' || this == '_' || this == '.' || this == '-'
