package com.garfiec.librechat.core.network.client

import io.ktor.http.DEFAULT_PORT
import io.ktor.http.URLBuilder
import io.ktor.http.Url

/**
 * True when [requestHost] belongs to the server [baseUrl], so the **session bearer** is safe to
 * attach. Returns true when host-scoping is disabled ([baseUrl] null — no provider and no snapshot)
 * or the base URL isn't resolved yet (empty host); the latter only happens during cold-start warm-up,
 * before any cross-host CDN fetch can occur, so it preserves same-origin auth without leaking.
 *
 * [baseUrl] is the request's snapshotted server URL when the [SwitchBarrierPlugin] is installed, so an
 * account switch mid-request scopes the bearer to the account the request was snapshotted for (never
 * the live one), keeping A's bearer off B's host.
 *
 * Deliberately host-only and fail-*open*: this is the pre-existing bearer rule, kept unchanged.
 * Custom gateway headers use [isSameServerAuthority] instead, which is stricter on both counts.
 */
internal fun isSameHostAsServer(requestHost: String, baseUrl: String?): Boolean {
    if (baseUrl.isNullOrEmpty()) return true
    val baseHost = runCatching { Url(baseUrl).host }.getOrNull()
    if (baseHost.isNullOrEmpty()) return true
    return requestHost.equals(baseHost, ignoreCase = true)
}

/**
 * True when [requestUrl] addresses exactly the server [baseUrl] identifies — **scheme, host and
 * effective port all matching**. The gate for user-configured gateway headers.
 *
 * Stricter than [isSameHostAsServer] in two ways, both deliberate:
 *
 * - **Scheme and port count.** A same-host `http://` downgrade would otherwise pass, putting a
 *   long-lived, non-rotating secret on the wire in cleartext. The session bearer is short-lived and
 *   rotates; a gateway service token is neither, so the blast radius of one leak is the whole
 *   deployment rather than one session.
 * - **Fail closed.** An unknown or unparseable base URL yields false, so a request whose server can't
 *   be established carries no credential. [isSameHostAsServer] fails open to preserve legacy
 *   cold-start behaviour for the bearer; there is no equivalent legacy to preserve here.
 */
internal fun isSameServerAuthority(requestUrl: URLBuilder, baseUrl: String?): Boolean {
    if (baseUrl.isNullOrEmpty()) return false
    val base = runCatching { Url(baseUrl) }.getOrNull() ?: return false
    if (base.host.isEmpty() || requestUrl.host.isEmpty()) return false
    return requestUrl.host.equals(base.host, ignoreCase = true) &&
        requestUrl.protocol.name.equals(base.protocol.name, ignoreCase = true) &&
        requestUrl.effectivePort() == base.port
}

/**
 * The port a [URLBuilder] will actually dial. `URLBuilder.port` reports [DEFAULT_PORT] (a sentinel,
 * not a real port) when no port was written explicitly, whereas `Url.port` already resolves to the
 * protocol default — comparing the two raw would make `https://host` and `https://host:443` differ.
 */
private fun URLBuilder.effectivePort(): Int =
    if (port == DEFAULT_PORT) protocol.defaultPort else port
