package com.garfiec.librechat.core.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.garfiec.librechat.core.common.identity.AccountState
import com.garfiec.librechat.core.common.identity.InMemoryActiveAccountProvider
import com.garfiec.librechat.core.data.datastore.AccountRoster
import com.garfiec.librechat.core.data.datastore.AccountScopedPrefsPurger
import com.garfiec.librechat.core.data.datastore.ServerDataStore
import com.garfiec.librechat.core.network.client.RefreshResult
import com.garfiec.librechat.core.network.client.SwitchGate
import com.garfiec.librechat.core.network.client.TokenManager
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.serialization.json.Json
import java.io.File

/** Records the staging/binding/removal operations the switch/add/remove flows drive; everything
 *  else is inert. Shared by the [AccountSwitcher] behavior tests. */
internal class RecordingTokenManager : TokenManager {
    var stagedAccess: String? = null
    var resolvedAccount: String? = null
    var clearedActive = false
    val selections = mutableListOf<String>()
    val removedAccounts = mutableListOf<String>()
    val expiredEmissions = mutableListOf<String?>()

    override val isAuthenticated: Boolean = true
    override suspend fun getAccessToken(): String? = null
    override suspend fun setTokens(accessToken: String, refreshToken: String) {
        stagedAccess = accessToken
    }
    override suspend fun refreshAccessToken(): RefreshResult = RefreshResult.HardExpired
    override suspend fun clearTokens() {}
    override suspend fun getAccessTokenFor(accountId: String): String? = null
    override suspend fun getStagedAccessToken(): String? = stagedAccess
    override suspend fun clearStagedTokens() {
        stagedAccess = null
    }
    override suspend fun selectAccount(accountId: String) {
        selections += accountId
    }
    override suspend fun removeAccount(accountId: String) {
        removedAccounts += accountId
    }
    override suspend fun refreshAccessTokenFor(accountId: String, baseUrl: String): RefreshResult = RefreshResult.HardExpired
    override suspend fun onAccountResolved(accountId: String) {
        resolvedAccount = accountId
        stagedAccess = null
    }
    override suspend fun onAccountCleared() {
        clearedActive = true
    }
    override fun emitSessionExpired(expiredAccountId: String?) {
        expiredEmissions += expiredAccountId
    }
    override val sessionExpiredFlow: SharedFlow<Unit> = MutableSharedFlow()
}

internal class RecordingSwitchCacheCleaner : SwitchCacheCleaner {
    var clearCount = 0
    override suspend fun clearOnSwitch() {
        clearCount++
    }
}

internal class RecordingSessionCacheCleaner : SessionCacheCleaner {
    var fileClearCount = 0
    override fun clearFileCaches() {
        fileClearCount++
    }
}

/** Real roster/server/prefs stores over a tmp DataStore file + recording fakes, wired into a real
 *  [AccountSwitcher] under a real [SwitchGate]. */
internal class SwitcherHarness(
    tmp: File,
    dispatcher: TestDispatcher,
    json: Json,
    initialState: AccountState,
    rosterOverride: AccountRoster? = null,
) {
    val dataStore: DataStore<Preferences> =
        PreferenceDataStoreFactory.create { File(tmp, "switcher.preferences_pb") }
    val roster = rosterOverride ?: AccountRoster(dataStore, json)
    val serverDataStore = ServerDataStore(dataStore, CoroutineScope(dispatcher), dispatcher, null)
    val tokenManager = RecordingTokenManager()
    val provider = InMemoryActiveAccountProvider(initialState)
    val claimReconciler = mockk<AccountClaimReconciler>(relaxed = true)
    val switchCacheCleaner = RecordingSwitchCacheCleaner()
    val dataPurger = mockk<AccountDataPurger>(relaxed = true)
    val prefsPurger = AccountScopedPrefsPurger(dataStore)
    val sessionCacheCleaner = RecordingSessionCacheCleaner()
    val switcher = AccountSwitcher(
        roster = roster,
        serverDataStore = serverDataStore,
        tokenManager = tokenManager,
        activeAccountProvider = provider,
        switchGate = SwitchGate(provider, serverDataStore, tokenManager, accountReadyGate = null),
        claimReconciler = claimReconciler,
        switchCacheCleaner = switchCacheCleaner,
        accountDataPurger = dataPurger,
        prefsPurger = prefsPurger,
        sessionCacheCleaner = sessionCacheCleaner,
    )
}
