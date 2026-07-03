package com.garfiec.librechat.core.data.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.garfiec.librechat.core.common.extensions.serverHostLabel
import com.garfiec.librechat.core.common.identity.deriveServerId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.time.Clock

/**
 * Device-global persistence of the **account roster** (multi-account, issue #179): the serialized list
 * of [AccountEntry] plus the single `active_account_id` pointer.
 *
 * The active pointer **reuses the pre-roster key** (`active_account_id`, written by the single-active
 * builds through #206), so the roster *subsumes* it — there is exactly one active-account pointer, not
 * a roster pointer diverging from a legacy one. Every mutation is a single [DataStore.edit] transform
 * (deserialize → modify → reserialize inside the block) so concurrent writers can't lose an update to a
 * read-modify-write race.
 *
 * This class is pure storage. Cold-start reconciliation (mirror-follows-roster), URL driving, and
 * identity publishing live in [AccountRegistry], which owns the seed coroutine.
 */
class AccountRoster(
    private val dataStore: DataStore<Preferences>,
    private val json: Json,
) {

    /** A consistent point-in-time read of the entries + active pointer. */
    suspend fun snapshot(): RosterSnapshot =
        dataStore.data.map { prefs -> prefs.toSnapshot() }.first()

    /** True when [accountId] is still a known account (present in the roster). Drives the
     *  origin-capture "stamp only if still live, else skip" rule so a write captured for an account
     *  that has since been removed doesn't resurrect its purged rows. */
    suspend fun contains(accountId: String): Boolean =
        dataStore.data.map { prefs -> prefs.readEntries().any { it.accountId == accountId } }.first()

    /** The roster entries, for the switcher UI. Decodes only when the serialized roster actually
     *  changes — this maps the app's *shared* settings DataStore, so an unrelated preference write
     *  would otherwise re-parse the roster JSON on every emission while the drawer is subscribed. */
    fun entriesFlow(): Flow<List<AccountEntry>> =
        dataStore.data
            .map { prefs -> prefs[KEY_ROSTER] }
            .distinctUntilChanged()
            .map { serialized -> decodeEntries(serialized) }

    /**
     * Upsert [entry] (replacing any existing entry with the same [AccountEntry.accountId]) and mark it
     * active — one atomic edit. The login / add / switch-target write path.
     */
    suspend fun upsertAndActivate(entry: AccountEntry) {
        dataStore.edit { prefs ->
            val entries = prefs.readEntries().filterNot { it.accountId == entry.accountId } + entry
            prefs[KEY_ROSTER] = json.encodeToString(listSerializer, entries)
            prefs[KEY_ACTIVE_ACCOUNT_ID] = entry.accountId
        }
    }

    /**
     * Mark [accountId] active and bump its `lastActiveAt` — one atomic edit. The switch-target write
     * path: the entry already exists, so (unlike [upsertAndActivate]) this only repoints the active
     * pointer and refreshes recency, never adds or overwrites entry fields. No-op if [accountId] isn't
     * in the roster.
     */
    suspend fun activate(accountId: String) {
        dataStore.edit { prefs ->
            val entries = prefs.readEntries()
            if (entries.none { it.accountId == accountId }) return@edit
            val now = Clock.System.now().toEpochMilliseconds()
            val updated = entries.map { if (it.accountId == accountId) it.copy(lastActiveAt = now) else it }
            prefs[KEY_ROSTER] = json.encodeToString(listSerializer, updated)
            prefs[KEY_ACTIVE_ACCOUNT_ID] = accountId
        }
    }

    /**
     * Refresh [accountId]'s display label + avatar — one atomic edit, touching nothing else (no
     * activation, no recency bump). The session-start backfill path: a migrated pre-roster entry is
     * seeded with the server-host fallback (no `User` payload exists at migration time), and a
     * stay-logged-in user never re-runs the login path that would overwrite it. No-op if
     * [accountId] isn't in the roster.
     */
    suspend fun updateDisplay(accountId: String, displayLabel: String, avatarUrl: String?) {
        dataStore.edit { prefs ->
            val entries = prefs.readEntries()
            if (entries.none { it.accountId == accountId }) return@edit
            val updated = entries.map {
                if (it.accountId == accountId) it.copy(displayLabel = displayLabel, avatarUrl = avatarUrl) else it
            }
            prefs[KEY_ROSTER] = json.encodeToString(listSerializer, updated)
        }
    }

    /**
     * Remove [accountId] from the roster; if it was the active account, clear the active pointer too —
     * one atomic edit. The logout / remove-account write path.
     */
    suspend fun removeAndDeactivate(accountId: String) {
        dataStore.edit { prefs ->
            val entries = prefs.readEntries().filterNot { it.accountId == accountId }
            prefs[KEY_ROSTER] = json.encodeToString(listSerializer, entries)
            if (prefs[KEY_ACTIVE_ACCOUNT_ID] == accountId) prefs.remove(KEY_ACTIVE_ACCOUNT_ID)
        }
    }

    /**
     * One-time migration from the pre-roster single-active pointer into a one-entry roster. Guarded by a
     * durable [KEY_MIGRATED] marker written in the **same edit**, so it (a) never resurrects a removed
     * account from the still-present legacy pointer on a later launch, and (b) never runs twice.
     *
     * Seeds an entry only when both the legacy [KEY_ACTIVE_ACCOUNT_ID] and a non-blank [legacyServerUrl]
     * are present **and** `deriveServerId(legacyServerUrl)` still matches the account's own serverId
     * (its `serverId:userKey` prefix). A mismatch (the server URL was edited, or an iOS keychain
     * reinstall left an id whose server can't be re-derived) can't be repaired — `serverId` is an
     * irreversible hash — so the only honest action is to drop the active pointer and let the user log
     * in again, orphaning that account's at-rest tokens/rows rather than binding them to the wrong URL.
     */
    suspend fun migrateIfNeeded(legacyServerUrl: String) {
        dataStore.edit { prefs ->
            if (prefs[KEY_MIGRATED] != null) return@edit
            prefs[KEY_MIGRATED] = MIGRATED_VALUE
            val legacyActive = prefs[KEY_ACTIVE_ACCOUNT_ID]?.takeIf { it.isNotBlank() } ?: return@edit
            val serverId = legacyActive.substringBefore(':')
            if (legacyServerUrl.isBlank() || deriveServerId(legacyServerUrl).value != serverId) {
                // Unrepairable url↔account mismatch → route to login instead of binding a wrong server.
                prefs.remove(KEY_ACTIVE_ACCOUNT_ID)
                return@edit
            }
            val entry = AccountEntry(
                accountId = legacyActive,
                serverUrl = legacyServerUrl,
                displayLabel = legacyServerUrl.serverHostLabel(),
                avatarUrl = null,
                lastActiveAt = 0L,
            )
            prefs[KEY_ROSTER] = json.encodeToString(listSerializer, listOf(entry))
        }
    }

    private fun Preferences.readEntries(): List<AccountEntry> = decodeEntries(this[KEY_ROSTER])

    private fun decodeEntries(serialized: String?): List<AccountEntry> =
        serialized?.let {
            runCatching { json.decodeFromString(listSerializer, it) }.getOrDefault(emptyList())
        } ?: emptyList()

    private fun Preferences.toSnapshot(): RosterSnapshot =
        RosterSnapshot(readEntries(), this[KEY_ACTIVE_ACCOUNT_ID]?.takeIf { it.isNotBlank() })

    private companion object {
        // Reuse the pre-roster pointer key so #206 installs upgrade with the active account intact.
        val KEY_ACTIVE_ACCOUNT_ID = stringPreferencesKey("active_account_id")
        val KEY_ROSTER = stringPreferencesKey("account_roster")
        val KEY_MIGRATED = stringPreferencesKey("account_roster_migrated")
        const val MIGRATED_VALUE = "1"
        val listSerializer = ListSerializer(AccountEntry.serializer())
    }
}
