package com.garfiec.librechat.core.common.identity

import kotlin.jvm.JvmInline

/**
 * Stable, opaque identity of a single account bucket — one (server, user) pair — for logical
 * row-tenancy.
 *
 * Wrapping the raw string in a value class is deliberate: an [AccountId] is the only thing allowed to
 * key tenant rows / prefixed prefs, so a bare `mongoId` or server URL cannot be passed where an
 * account owner is expected. Derivation (`serverId:userKey`) lands with the persisted registry; this
 * type only guarantees the value is non-blank.
 */
@JvmInline
value class AccountId(val value: String) {
    init {
        require(value.isNotBlank()) { "AccountId must not be blank" }
    }

    override fun toString(): String = value
}

/**
 * The resolution state of the active account, observed as a [kotlinx.coroutines.flow.StateFlow].
 *
 * Two distinct non-resolved meanings, which must not be conflated:
 * - [Warming] is a **boot-only** sentinel — the persisted active account hasn't been loaded yet, so
 *   "which account" is genuinely unknown. Identity-dependent work must wait, never assume logged-out.
 * - [Resolved] with `id == null` is the **logged-out** signal — identity is known to be "nobody".
 *
 * Consumers that need a live account gate on `state.first { it is Resolved && it.id != null }`
 * (re-evaluable per call across login→logout→login epochs), never on `it != Warming` (which would
 * admit `Resolved(null)` and build a null-id session) and never on a one-shot deferred.
 */
sealed interface AccountState {
    data object Warming : AccountState
    data class Resolved(val id: AccountId?) : AccountState
}
