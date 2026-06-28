package com.garfiec.librechat.core.common.identity

import okio.ByteString.Companion.encodeUtf8
import kotlin.jvm.JvmInline

/**
 * Stable identity of a server *deployment* — `serverId = hash(normalizeServerUrl(url))`. Used as the
 * prefix for server-scoped prefs and as the first half of an [AccountId], so the same Mongo user on
 * two different deployments resolves to two different accounts.
 */
@JvmInline
value class ServerId(val value: String) {
    init {
        require(value.isNotBlank()) { "ServerId must not be blank" }
    }

    override fun toString(): String = value
}

/** Truncation of the SHA-256 hex digest used for [ServerId]; 64 bits is collision-proof for the
 * handful of distinct server URLs a user adds, while keeping keys compact. */
private const val SERVER_ID_HEX_LENGTH = 16

/**
 * Derives the [ServerId] for a server URL. Runs the URL through [normalizeServerUrl] first so that
 * trailing-slash / case / default-port variants of the *same* deployment collapse to one id (and a
 * reverse-proxy subpath stays distinct), then hashes the canonical form. The hash is purely to get a
 * compact, key-safe, opaque token — the normalization is what actually defines deployment identity.
 */
fun deriveServerId(rawUrl: String): ServerId =
    ServerId(normalizeServerUrl(rawUrl).encodeUtf8().sha256().hex().take(SERVER_ID_HEX_LENGTH))

/**
 * Composes the [AccountId] for a (server, user) pair. `userKey` is the user's stable backend id
 * (Mongo `_id`); it must be non-blank — a blank user key would collapse distinct logged-out/unknown
 * states into one bucket. The format is `serverId:userKey`.
 */
fun deriveAccountId(serverId: ServerId, userKey: String): AccountId {
    require(userKey.isNotBlank()) { "userKey must not be blank" }
    return AccountId("${serverId.value}:$userKey")
}
