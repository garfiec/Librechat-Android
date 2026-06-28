package com.garfiec.librechat.core.data.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.garfiec.librechat.core.common.identity.AccountId
import com.garfiec.librechat.core.common.identity.AccountState.Resolved
import com.garfiec.librechat.core.common.identity.InMemoryActiveAccountProvider
import com.garfiec.librechat.core.network.client.ServerUrlProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class AccountRegistryTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private val testDispatcher = UnconfinedTestDispatcher()

    private fun createDataStore(name: String): DataStore<Preferences> =
        PreferenceDataStoreFactory.create { File(tmpFolder.root, "$name.preferences_pb") }

    private val serverUrlProvider = object : ServerUrlProvider {
        override fun getBaseUrl(): String = "https://chat.example.com"
        override suspend fun awaitBaseUrl(): String = "https://chat.example.com"
    }

    @Test
    fun coldStart_emptyRegistry_seedsLoggedOut() = runTest(testDispatcher) {
        val provider = InMemoryActiveAccountProvider()
        val registry = AccountRegistry(
            dataStore = createDataStore("acct-empty"),
            activeAccountProvider = provider,
            serverUrlProvider = serverUrlProvider,
            appScope = CoroutineScope(testDispatcher),
            ioDispatcher = testDispatcher,
        )
        registry.awaitSeeded()
        // Resolved(null) = known logged-out, NOT left Warming.
        assertThat(provider.state.value).isEqualTo(Resolved(null))
    }

    @Test
    fun coldStart_persistedId_seedsThatAccount() = runTest(testDispatcher) {
        val ds = createDataStore("acct-persisted")
        // First instance persists an active account...
        AccountRegistry(ds, InMemoryActiveAccountProvider(), serverUrlProvider, CoroutineScope(testDispatcher), testDispatcher)
            .apply { awaitSeeded() }
            .setActiveAccount(AccountId("srv:user-1"))

        // ...a fresh instance over the same store seeds it at cold start.
        val provider = InMemoryActiveAccountProvider()
        AccountRegistry(ds, provider, serverUrlProvider, CoroutineScope(testDispatcher), testDispatcher)
            .awaitSeeded()
        assertThat(provider.state.value).isEqualTo(Resolved(AccountId("srv:user-1")))
    }

    @Test
    fun setActiveAccount_persistsAndPublishes() = runTest(testDispatcher) {
        val provider = InMemoryActiveAccountProvider()
        val registry = AccountRegistry(
            createDataStore("acct-set"), provider, serverUrlProvider, CoroutineScope(testDispatcher), testDispatcher,
        )
        registry.awaitSeeded()
        registry.setActiveAccount(AccountId("srv:user-A"))
        assertThat(provider.state.value).isEqualTo(Resolved(AccountId("srv:user-A")))
    }

    @Test
    fun clearActiveAccount_flipsToNullAndForgetsOnDisk() = runTest(testDispatcher) {
        val ds = createDataStore("acct-clear")
        val provider = InMemoryActiveAccountProvider()
        val registry = AccountRegistry(ds, provider, serverUrlProvider, CoroutineScope(testDispatcher), testDispatcher)
        registry.awaitSeeded()
        registry.setActiveAccount(AccountId("srv:user-A"))

        registry.clearActiveAccount()
        assertThat(provider.state.value).isEqualTo(Resolved(null))

        // A fresh cold start over the same store stays logged-out (disk was cleared).
        val reSeed = InMemoryActiveAccountProvider()
        AccountRegistry(ds, reSeed, serverUrlProvider, CoroutineScope(testDispatcher), testDispatcher).awaitSeeded()
        assertThat(reSeed.state.value).isEqualTo(Resolved(null))
    }
}
