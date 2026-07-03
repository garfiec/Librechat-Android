package com.garfiec.librechat.core.data.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit

/**
 * Drops every `acct:<accountId>:*` preference for one account (last-used model/endpoint, cached role
 * permissions, and any future account-scoped key) — the preferences leg of account removal. Prefix-
 * matched rather than base-enumerated, so a newly added account-scoped preference is covered without
 * remembering to register it here. Server-scoped (`srv:`) config caches are deliberately untouched:
 * they belong to the server, which other retained accounts may share.
 */
class AccountScopedPrefsPurger(private val dataStore: DataStore<Preferences>) {

    suspend fun purge(accountId: String) {
        dataStore.edit { prefs -> prefs.removeAllForAccount(accountId) }
    }
}
