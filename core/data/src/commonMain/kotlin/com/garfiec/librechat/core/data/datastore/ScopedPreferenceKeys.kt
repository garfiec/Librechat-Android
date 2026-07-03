package com.garfiec.librechat.core.data.datastore

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey

/**
 * The single definition of the row-tenancy persistence-key namespace (`acct:<accountId>:<base>` /
 * `srv:<serverId>:<base>`), shared by the token store and the account/server-scoped preference
 * stores. Keeping the scheme in one place means the writer and the reader can never drift onto
 * different key formats — a drift would silently read the wrong (or no) account's value.
 */

internal fun accountScopedName(accountId: String, base: String): String = "acct:$accountId:$base"

internal fun serverScopedName(serverId: String, base: String): String = "srv:$serverId:$base"

internal fun accountScopedKey(accountId: String, base: String): Preferences.Key<String> =
    stringPreferencesKey(accountScopedName(accountId, base))

internal fun serverScopedKey(serverId: String, base: String): Preferences.Key<String> =
    stringPreferencesKey(serverScopedName(serverId, base))

/**
 * Removes every `acct:<accountId>:*` entry for one account, whatever its base — complete by
 * construction, so a new account-scoped preference can never be forgotten by the removal path.
 */
internal fun MutablePreferences.removeAllForAccount(accountId: String) {
    val prefix = accountScopedName(accountId, "")
    asMap().keys
        .filter { it.name.startsWith(prefix) }
        .toList()
        .forEach { remove(it) }
}
