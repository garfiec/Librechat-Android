package com.garfiec.librechat.core.data.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.garfiec.librechat.core.common.identity.AccountId
import com.garfiec.librechat.core.common.identity.AccountState.Resolved
import com.garfiec.librechat.core.common.identity.InMemoryActiveAccountProvider
import com.garfiec.librechat.core.common.identity.deriveAccountId
import com.garfiec.librechat.core.common.identity.deriveServerId
import com.garfiec.librechat.core.network.client.RefreshResult
import com.garfiec.librechat.core.network.client.TokenManager
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class AccountRegistryTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private val testDispatcher = UnconfinedTestDispatcher()
    private val json = Json { ignoreUnknownKeys = true }
    private val serverUrl = "https://chat.example.com"
    private val accountId = deriveAccountId(deriveServerId(serverUrl), "user-1")
    private val activeKey = stringPreferencesKey("active_account_id")

    private fun createDataStore(name: String): DataStore<Preferences> =
        PreferenceDataStoreFactory.create { File(tmpFolder.root, "$name.preferences_pb") }

    private fun serverDataStore(ds: DataStore<Preferences>) =
        ServerDataStore(ds, CoroutineScope(testDispatcher), testDispatcher, null)

    private fun registry(
        ds: DataStore<Preferences>,
        provider: InMemoryActiveAccountProvider,
        server: ServerDataStore,
        token: TokenManager = FakeTokenManager(),
    ) = AccountRegistry(
        roster = AccountRoster(ds, json),
        activeAccountProvider = provider,
        serverDataStore = server,
        tokenManager = token,
        appScope = CoroutineScope(testDispatcher),
        ioDispatcher = testDispatcher,
    )

    private fun entry() = AccountEntry(
        accountId = accountId.value,
        serverUrl = serverUrl,
        displayLabel = "Alice",
        avatarUrl = null,
        lastActiveAt = 1L,
    )

    @Test
    fun coldStart_emptyRoster_seedsLoggedOut_withoutTouchingTokens() = runTest(testDispatcher) {
        val ds = createDataStore("empty")
        val provider = InMemoryActiveAccountProvider()
        val token = FakeTokenManager()
        registry(ds, provider, serverDataStore(ds), token).awaitReady()

        assertThat(provider.state.value).isEqualTo(Resolved(null))
        // An empty roster must NOT clear the token store: a pre-tenancy upgrade has an empty roster
        // while the live session sits under the bare keys, waiting for restoreAccountIfNeeded.
        assertThat(token.cleared).isFalse()
        assertThat(token.selected).isNull()
    }

    @Test
    fun coldStart_migratesLegacyPointer_publishesAndReconcilesMirror() = runTest(testDispatcher) {
        val ds = createDataStore("legacy")
        val server = serverDataStore(ds)
        server.setServerUrl(serverUrl)
        ds.edit { it[activeKey] = accountId.value } // pre-roster (#206) install: pointer + URL only

        val provider = InMemoryActiveAccountProvider()
        val token = FakeTokenManager()
        registry(ds, provider, server, token).awaitReady()

        assertThat(provider.state.value).isEqualTo(Resolved(accountId))
        // Mirror reconciled to the authority, not cleared.
        assertThat(token.selected).isEqualTo(accountId.value)
        assertThat(token.cleared).isFalse()
        // Migration produced a one-entry roster.
        assertThat(AccountRoster(ds, json).snapshot().entries.map { it.accountId })
            .containsExactly(accountId.value)
    }

    @Test
    fun upsertActive_persistsRosterEntry_survivesColdRestart() = runTest(testDispatcher) {
        val ds = createDataStore("login")
        val server = serverDataStore(ds)
        server.setServerUrl(serverUrl)
        val provider = InMemoryActiveAccountProvider()
        val reg = registry(ds, provider, server)
        reg.awaitReady()

        reg.upsertActive(entry())
        assertThat(provider.state.value).isEqualTo(Resolved(accountId))

        // Fresh instance over the same store re-seeds the account (marker already set -> no clobber).
        val provider2 = InMemoryActiveAccountProvider()
        registry(ds, provider2, serverDataStore(ds)).awaitReady()
        assertThat(provider2.state.value).isEqualTo(Resolved(accountId))
    }

    @Test
    fun clearActiveAccount_flipsToNull_andStaysLoggedOutOnRestart() = runTest(testDispatcher) {
        val ds = createDataStore("clear")
        val server = serverDataStore(ds)
        server.setServerUrl(serverUrl)
        val provider = InMemoryActiveAccountProvider()
        val reg = registry(ds, provider, server)
        reg.awaitReady()
        reg.upsertActive(entry())

        reg.clearActiveAccount()
        assertThat(provider.state.value).isEqualTo(Resolved(null))

        val provider2 = InMemoryActiveAccountProvider()
        registry(ds, provider2, serverDataStore(ds)).awaitReady()
        assertThat(provider2.state.value).isEqualTo(Resolved(null))
    }

    /** Records the reconcile calls the seed makes; every other method is an inert stub. */
    private class FakeTokenManager : TokenManager {
        var selected: String? = null
        var cleared = false

        override val isAuthenticated: Boolean = false
        override suspend fun getAccessToken(): String? = null
        override suspend fun setTokens(accessToken: String, refreshToken: String) {}
        override suspend fun refreshAccessToken(): RefreshResult = RefreshResult.HardExpired
        override suspend fun clearTokens() {}
        override suspend fun getAccessTokenFor(accountId: String): String? = null
        override suspend fun getStagedAccessToken(): String? = null
        override suspend fun clearStagedTokens() {}
        override suspend fun selectAccount(accountId: String) { selected = accountId }
        override suspend fun removeAccount(accountId: String) {}
        override suspend fun refreshAccessTokenFor(accountId: String, baseUrl: String): RefreshResult = RefreshResult.HardExpired
        override suspend fun onAccountResolved(accountId: String) {}
        override suspend fun onAccountCleared() { cleared = true }
        override fun emitSessionExpired(expiredAccountId: String?) {}
        override val sessionExpiredFlow: SharedFlow<Unit> = MutableSharedFlow()
    }
}
