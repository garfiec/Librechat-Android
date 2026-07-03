package com.garfiec.librechat.core.data.datastore

import kotlinx.serialization.Serializable

/**
 * One signed-in account in the persisted roster (multi-account, issue #179). Device-global metadata
 * — it carries no tenant data, only what the switcher UI and the switch transition need:
 *
 * - [accountId] — the `serverId:userKey` identity; the roster key and the token/prefs scope prefix.
 *   The `serverId` half is derivable from here (`accountId.substringBefore(':')`) so it is not stored.
 * - [serverUrl] — the deployment's base URL. **The only place the `serverId → serverUrl` map lives**:
 *   `serverId` is an irreversible hash, so a switch reads the target's URL from here to repoint the
 *   network layer.
 * - [displayLabel] / [avatarUrl] — what the switcher chip shows; seeded from the `User` payload at
 *   login, or the server host as a fallback on the migration path (no `User` available then).
 * - [lastActiveAt] — epoch millis of the last activation; drives "switch to most-recently-active" when
 *   the active account is removed.
 */
@Serializable
data class AccountEntry(
    val accountId: String,
    val serverUrl: String,
    val displayLabel: String,
    val avatarUrl: String? = null,
    val lastActiveAt: Long,
)

/** A consistent read of the roster: its [entries] plus the [activeId] pointer. */
data class RosterSnapshot(
    val entries: List<AccountEntry>,
    val activeId: String?,
) {
    /** The active entry, or null while logged out / when the pointer has no backing entry. */
    val activeEntry: AccountEntry? get() = entries.firstOrNull { it.accountId == activeId }
}
