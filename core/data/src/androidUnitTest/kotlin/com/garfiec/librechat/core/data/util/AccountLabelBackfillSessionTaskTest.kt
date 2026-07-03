package com.garfiec.librechat.core.data.util

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.garfiec.librechat.core.common.identity.AccountState
import com.garfiec.librechat.core.common.identity.InMemoryActiveAccountProvider
import com.garfiec.librechat.core.common.identity.deriveAccountId
import com.garfiec.librechat.core.common.identity.deriveServerId
import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.data.datastore.AccountEntry
import com.garfiec.librechat.core.data.datastore.AccountRoster
import com.garfiec.librechat.core.data.repository.UserRepository
import com.garfiec.librechat.core.model.User
import com.garfiec.librechat.core.network.client.ServerUrlProvider
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AccountLabelBackfillSessionTaskTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private val serverUrl = "https://chat.example.com"
    private val accountId = deriveAccountId(deriveServerId(serverUrl), "user-1")

    private val userRepository = mockk<UserRepository>()
    private val serverUrlProvider = mockk<ServerUrlProvider> {
        coEvery { awaitBaseUrl() } returns serverUrl
    }

    private fun roster() = AccountRoster(
        PreferenceDataStoreFactory.create { File(tmpFolder.root, "backfill.preferences_pb") },
        Json { ignoreUnknownKeys = true },
    )

    @Test
    fun `backfills the migrated host-fallback label from the live user`() = runTest {
        val roster = roster()
        roster.upsertAndActivate(
            AccountEntry(
                accountId = accountId.value,
                serverUrl = serverUrl,
                displayLabel = "chat.example.com", // migration seeded the host fallback
                avatarUrl = null,
                lastActiveAt = 1L,
            ),
        )
        coEvery { userRepository.getUser() } returns
            Result.Success(User(id = "user-1", name = "Alice", email = "alice@example.com", avatar = "https://a/p.png"))

        AccountLabelBackfillSessionTask(
            userRepository = userRepository,
            accountRoster = roster,
            activeAccountProvider = InMemoryActiveAccountProvider(AccountState.Resolved(accountId)),
            serverUrlProvider = serverUrlProvider,
        ).run()

        val entry = roster.snapshot().activeEntry
        assertThat(entry?.displayLabel).isEqualTo("Alice")
        assertThat(entry?.avatarUrl).isEqualTo("https://a/p.png")
    }

    @Test
    fun `skips when the account is unresolved or the user fetch fails`() = runTest {
        val roster = roster()
        roster.upsertAndActivate(
            AccountEntry(
                accountId = accountId.value,
                serverUrl = serverUrl,
                displayLabel = "chat.example.com",
                avatarUrl = null,
                lastActiveAt = 1L,
            ),
        )
        coEvery { userRepository.getUser() } returns Result.Error(message = "offline")

        // Unresolved account: getUser must not even matter.
        AccountLabelBackfillSessionTask(
            userRepository = userRepository,
            accountRoster = roster,
            activeAccountProvider = InMemoryActiveAccountProvider(AccountState.Warming),
            serverUrlProvider = serverUrlProvider,
        ).run()
        // Resolved but the fetch failed: keep the fallback label.
        AccountLabelBackfillSessionTask(
            userRepository = userRepository,
            accountRoster = roster,
            activeAccountProvider = InMemoryActiveAccountProvider(AccountState.Resolved(accountId)),
            serverUrlProvider = serverUrlProvider,
        ).run()

        assertThat(roster.snapshot().activeEntry?.displayLabel).isEqualTo("chat.example.com")
    }
}
