package com.garfiec.librechat.core.data.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.garfiec.librechat.core.common.identity.deriveAccountId
import com.garfiec.librechat.core.common.identity.deriveServerId
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class AccountRosterTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true }
    private val serverUrl = "https://chat.example.com"
    private val accountId = deriveAccountId(deriveServerId(serverUrl), "user-1").value
    private val activeKey = stringPreferencesKey("active_account_id")

    private fun createDataStore(name: String): DataStore<Preferences> =
        PreferenceDataStoreFactory.create { File(tmpFolder.root, "$name.preferences_pb") }

    private fun entry(id: String = accountId, label: String = "Alice") =
        AccountEntry(
            accountId = id,
            serverUrl = serverUrl,
            displayLabel = label,
            avatarUrl = null,
            lastActiveAt = 1L,
        )

    @Test
    fun upsertAndActivate_addsEntryAndPointer_replacingSameId() = runTest {
        val roster = AccountRoster(createDataStore("upsert"), json)
        roster.upsertAndActivate(entry(label = "Alice"))
        roster.upsertAndActivate(entry(label = "Alice Renamed")) // same accountId -> replace, not dup

        val snap = roster.snapshot()
        assertThat(snap.activeId).isEqualTo(accountId)
        assertThat(snap.entries).hasSize(1)
        assertThat(snap.activeEntry?.displayLabel).isEqualTo("Alice Renamed")
    }

    @Test
    fun updateDisplay_refreshesLabelAndAvatar_withoutTouchingActivationOrRecency() = runTest {
        val roster = AccountRoster(createDataStore("update-display"), json)
        roster.upsertAndActivate(entry(label = "chat.example.com")) // migration-style host fallback

        roster.updateDisplay(accountId, displayLabel = "Alice", avatarUrl = "https://a/avatar.png")

        val snap = roster.snapshot()
        assertThat(snap.activeEntry?.displayLabel).isEqualTo("Alice")
        assertThat(snap.activeEntry?.avatarUrl).isEqualTo("https://a/avatar.png")
        assertThat(snap.activeEntry?.lastActiveAt).isEqualTo(1L) // recency untouched
        assertThat(snap.activeId).isEqualTo(accountId)
    }

    @Test
    fun updateDisplay_unknownAccount_isNoOp() = runTest {
        val roster = AccountRoster(createDataStore("update-display-miss"), json)
        roster.upsertAndActivate(entry(label = "Alice"))

        roster.updateDisplay("other:account", displayLabel = "Mallory", avatarUrl = null)

        val snap = roster.snapshot()
        assertThat(snap.entries).hasSize(1)
        assertThat(snap.activeEntry?.displayLabel).isEqualTo("Alice")
    }

    @Test
    fun removeAndDeactivate_dropsEntry_andClearsPointerWhenActive() = runTest {
        val roster = AccountRoster(createDataStore("remove"), json)
        roster.upsertAndActivate(entry())

        roster.removeAndDeactivate(accountId)

        val snap = roster.snapshot()
        assertThat(snap.entries).isEmpty()
        assertThat(snap.activeId).isNull()
    }

    @Test
    fun migrateIfNeeded_serverIdMatch_seedsOneEntryFromLegacyPointer() = runTest {
        val ds = createDataStore("migrate-match")
        ds.edit { it[activeKey] = accountId } // a pre-roster (#206) install: pointer only
        val roster = AccountRoster(ds, json)

        roster.migrateIfNeeded(serverUrl)

        val snap = roster.snapshot()
        assertThat(snap.activeId).isEqualTo(accountId)
        assertThat(snap.entries.map { it.accountId }).containsExactly(accountId)
        assertThat(snap.activeEntry?.serverUrl).isEqualTo(serverUrl)
    }

    @Test
    fun migrateIfNeeded_serverIdMismatch_dropsPointerAndSeedsNoEntry() = runTest {
        val ds = createDataStore("migrate-mismatch")
        ds.edit { it[activeKey] = accountId } // accountId's serverId derives from `serverUrl`...
        val roster = AccountRoster(ds, json)

        roster.migrateIfNeeded("https://different-server.example.org") // ...but the URL drifted

        val snap = roster.snapshot()
        assertThat(snap.activeId).isNull() // unrepairable -> route to login
        assertThat(snap.entries).isEmpty()
    }

    @Test
    fun migrateIfNeeded_isOneTime_doesNotResurrectARemovedAccount() = runTest {
        val ds = createDataStore("migrate-once")
        ds.edit { it[activeKey] = accountId }
        val roster = AccountRoster(ds, json)

        roster.migrateIfNeeded(serverUrl) // seeds the entry + marker
        roster.removeAndDeactivate(accountId) // user later removes it
        roster.migrateIfNeeded(serverUrl) // marker present -> must NOT re-seed from the (gone) pointer

        val snap = roster.snapshot()
        assertThat(snap.entries).isEmpty()
        assertThat(snap.activeId).isNull()
    }

    @Test
    fun migrateIfNeeded_noLegacyPointer_seedsEmptyRoster() = runTest {
        val roster = AccountRoster(createDataStore("migrate-empty"), json)

        roster.migrateIfNeeded(serverUrl)

        val snap = roster.snapshot()
        assertThat(snap.entries).isEmpty()
        assertThat(snap.activeId).isNull()
    }
}
