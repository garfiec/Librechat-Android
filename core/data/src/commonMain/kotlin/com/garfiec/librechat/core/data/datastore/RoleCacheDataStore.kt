package com.garfiec.librechat.core.data.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import co.touchlab.kermit.Logger
import com.garfiec.librechat.core.common.identity.ActiveAccountProvider
import com.garfiec.librechat.core.common.identity.currentAccountId
import com.garfiec.librechat.core.model.permissions.UserRolePermissions
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json

/**
 * Account-scoped role/permissions cache. Cached under `acct:<accountId>:<name>` so one account's
 * permissions can't be read under another (multi-account, issue #179). The role is fetched post-login
 * (account resolved), so a write while unresolved is skipped; a read while unresolved returns null and
 * the live fetch repopulates it.
 */
class RoleCacheDataStore(
    private val dataStore: DataStore<Preferences>,
    private val json: Json,
    private val activeAccountProvider: ActiveAccountProvider,
) {

    suspend fun save(role: UserRolePermissions) {
        val accountId = activeAccountProvider.currentAccountId()?.value ?: run {
            Logger.w { "No active account; skipping role cache write" }
            return
        }
        try {
            val serialized = json.encodeToString(UserRolePermissions.serializer(), role)
            dataStore.edit { prefs ->
                prefs[key(accountId)] = serialized
                prefs.remove(stringPreferencesKey(ROLE_BASE)) // drop the pre-keying bare entry once
            }
        } catch (e: Exception) {
            Logger.w(e) { "Failed to cache user role permissions" }
        }
    }

    suspend fun load(): UserRolePermissions? {
        val accountId = activeAccountProvider.currentAccountId()?.value ?: return null
        return try {
            val serialized = dataStore.data.first()[key(accountId)] ?: return null
            json.decodeFromString(UserRolePermissions.serializer(), serialized)
        } catch (e: Exception) {
            Logger.w(e) { "Failed to load cached user role permissions" }
            null
        }
    }

    suspend fun clear() {
        try {
            // Scope to the resolved account only: other roster accounts' cached roles are retained
            // multi-account state, not session garbage — a blanket wipe here would strip a retained
            // account's permissions on someone else's logout. On the logout/remove paths the active
            // account is already flipped to null when this runs; there its on-disk entry is reaped
            // by AccountScopedPrefsPurger instead, so an unresolved clear has nothing left to do.
            val accountId = activeAccountProvider.currentAccountId()?.value ?: return
            dataStore.edit { prefs -> prefs.remove(key(accountId)) }
        } catch (e: Exception) {
            Logger.w(e) { "Failed to clear cached user role permissions" }
        }
    }

    private fun key(accountId: String) = accountScopedKey(accountId, ROLE_BASE)

    private companion object {
        const val ROLE_BASE = "cached_user_role_permissions"
    }
}
