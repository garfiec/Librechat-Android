package com.garfiec.librechat.core.data.repository

import com.garfiec.librechat.core.common.identity.AccountState
import com.garfiec.librechat.core.common.identity.AccountState.Resolved
import com.garfiec.librechat.core.common.identity.deriveAccountId
import com.garfiec.librechat.core.common.identity.deriveServerId
import com.garfiec.librechat.core.data.datastore.AccountRoster
import com.garfiec.librechat.core.model.User
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import kotlin.test.assertFailsWith

/**
 * Behavior tests for [AccountSwitcher]'s add-account flow: staging hygiene at begin, the atomic
 * URL+token+roster+identity completion (derived from the *pending* URL, never the live one),
 * rollback on a failed completion, and cancel restoring the outgoing account's binding.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AccountSwitcherAddTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private val testDispatcher = UnconfinedTestDispatcher()
    private val json = Json { ignoreUnknownKeys = true }

    private val serverA = "https://a.example.com"
    private val serverB = "https://b.example.com"
    private val accountA = deriveAccountId(deriveServerId(serverA), "user-a")
    private val accountB = deriveAccountId(deriveServerId(serverB), "user-b")

    private fun harness(
        initialState: AccountState = Resolved(accountA),
        roster: AccountRoster? = null,
    ) = SwitcherHarness(tmpFolder.root, testDispatcher, json, initialState, roster)

    private fun userB() = User(id = "user-b", name = "Bea", email = "bea@example.com")

    @Test
    fun `beginAdd requires a resolved active account`() = runTest(testDispatcher) {
        val h = harness(initialState = AccountState.Warming)

        assertFailsWith<IllegalStateException> { h.switcher.beginAdd(serverB) }
    }

    @Test
    fun `beginAdd purges stale staging and rebinds the active account`() = runTest(testDispatcher) {
        val h = harness()
        h.tokenManager.stagedAccess = "stale-abandoned-login" // an earlier killed sign-in

        val session = h.switcher.beginAdd("$serverB/")

        assertThat(h.tokenManager.stagedAccess).isNull()
        assertThat(h.tokenManager.selections).containsExactly(accountA.value)
        assertThat(session.serverUrl).isEqualTo(serverB)
        assertThat(h.switcher.pendingAdd).isSameInstanceAs(session)
    }

    @Test
    fun `completeAdd derives from the pending URL and flips url+token+roster+identity`() =
        runTest(testDispatcher) {
            val h = harness()
            h.serverDataStore.setServerUrl(serverA)
            h.switcher.beginAdd(serverB)
            h.tokenManager.setTokens("b-access", "b-refresh") // the flow's sign-in staged B

            val added = h.switcher.completeAdd(userB())

            // Identity derives from the server being ADDED, not the still-live outgoing URL.
            assertThat(added).isEqualTo(accountB)
            assertThat(h.serverDataStore.getBaseUrl()).isEqualTo(serverB)
            assertThat(h.tokenManager.resolvedAccount).isEqualTo(accountB.value)
            val snapshot = h.roster.snapshot()
            assertThat(snapshot.activeId).isEqualTo(accountB.value)
            assertThat(snapshot.entries.single().displayLabel).isEqualTo("Bea")
            assertThat(h.provider.state.value).isEqualTo(Resolved(accountB))
            assertThat(h.switcher.pendingAdd).isNull()
        }

    @Test
    fun `completeAdd replaces the entry when the account is already in the roster`() =
        runTest(testDispatcher) {
            val h = harness()
            h.roster.upsertAndActivate(
                com.garfiec.librechat.core.data.datastore.AccountEntry(
                    accountId = accountB.value,
                    serverUrl = serverB,
                    displayLabel = "Old Label",
                    avatarUrl = null,
                    lastActiveAt = 1L,
                ),
            )
            h.switcher.beginAdd(serverB)

            h.switcher.completeAdd(userB())

            // Re-adding = switch-with-fresh-login: one entry, refreshed fields, no duplicate.
            val entries = h.roster.snapshot().entries.filter { it.accountId == accountB.value }
            assertThat(entries).hasSize(1)
            assertThat(entries.single().displayLabel).isEqualTo("Bea")
        }

    @Test
    fun `completeAdd rolls back the URL and token binding when the flip fails`() =
        runTest(testDispatcher) {
            val failingRoster = mockk<AccountRoster>()
            coEvery { failingRoster.upsertAndActivate(any()) } throws IOException("disk full")
            val h = harness(roster = failingRoster)
            h.serverDataStore.setServerUrl(serverA)
            h.switcher.beginAdd(serverB)
            h.tokenManager.setTokens("b-access", "b-refresh")

            assertFailsWith<IOException> { h.switcher.completeAdd(userB()) }

            // The half-applied flip was undone: URL back to A, binding re-selected to A, identity
            // never published, and the pending session retained for a retry/cancel.
            assertThat(h.serverDataStore.getBaseUrl()).isEqualTo(serverA)
            assertThat(h.tokenManager.selections.last()).isEqualTo(accountA.value)
            assertThat(h.provider.state.value).isEqualTo(Resolved(accountA))
            assertThat(h.switcher.pendingAdd).isNotNull()
        }

    @Test
    fun `cancelAdd drops the staged tokens and rebinds the active account`() = runTest(testDispatcher) {
        val h = harness()
        h.switcher.beginAdd(serverB)
        h.tokenManager.setTokens("b-access", "b-refresh")

        h.switcher.cancelAdd()

        assertThat(h.tokenManager.stagedAccess).isNull()
        assertThat(h.tokenManager.selections.last()).isEqualTo(accountA.value)
        assertThat(h.switcher.pendingAdd).isNull()
    }

    @Test
    fun `attachPendingConfig rides on the pending session and no-ops after cancel`() =
        runTest(testDispatcher) {
            val h = harness()
            val session = h.switcher.beginAdd(serverB)
            val config = com.garfiec.librechat.core.model.config.StartupConfig(serverDomain = serverB)

            h.switcher.attachPendingConfig(config)
            assertThat(session.startupConfig.value).isEqualTo(config)

            h.switcher.cancelAdd()
            h.switcher.attachPendingConfig(config.copy(serverDomain = "other"))
            // The cancelled session keeps whatever it had; nothing new is attached anywhere.
            assertThat(session.startupConfig.value).isEqualTo(config)
        }

    @Test
    fun `pending request identity serves the staged bearer once sign-in stages it`() =
        runTest(testDispatcher) {
            val h = harness()
            val session = h.switcher.beginAdd(serverB)

            // Before the sign-in call: no bearer (config validation / the login POST itself).
            assertThat(session.requestIdentity.identity().bearer).isNull()

            h.tokenManager.setTokens("b-access", "b-refresh")
            val identity = session.requestIdentity.identity()
            assertThat(identity.bearer).isEqualTo("b-access")
            assertThat(identity.baseUrl).isEqualTo(serverB)
            assertThat(identity.accountId).isNull()
            assertThat(identity.isPending).isTrue()
        }
}
