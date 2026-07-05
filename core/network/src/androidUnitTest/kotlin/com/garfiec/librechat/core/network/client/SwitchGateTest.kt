package com.garfiec.librechat.core.network.client

import com.garfiec.librechat.core.common.identity.AccountId
import com.garfiec.librechat.core.common.identity.AccountState.Resolved
import com.garfiec.librechat.core.common.identity.InMemoryActiveAccountProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.Test

/**
 * Snapshot semantics of [SwitchGate.captureSnapshot]: the bearer is keyed to the snapshot's account
 * (immune to another account's sign-in staging), and a [PendingRequestIdentity] in the coroutine
 * context short-circuits to the pending identity without touching the live providers or the gate.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SwitchGateTest {

    private class FakeTokenManager(
        private val liveToken: String?,
        private val keyed: Map<String, String> = emptyMap(),
    ) : TokenManager {
        override val isAuthenticated: Boolean get() = liveToken != null
        override suspend fun getAccessToken(): String? = liveToken
        override suspend fun setTokens(accessToken: String, refreshToken: String) = Unit
        override suspend fun refreshAccessToken(): RefreshResult = RefreshResult.HardExpired
        override suspend fun clearTokens() = Unit
        override suspend fun getAccessTokenFor(accountId: String): String? = keyed[accountId]
        override suspend fun getStagedAccessToken(): String? = null
        override suspend fun clearStagedTokens() = Unit
        override suspend fun selectAccount(accountId: String) = Unit
        override suspend fun removeAccount(accountId: String) = Unit
        override suspend fun refreshAccessTokenFor(accountId: String, baseUrl: String): RefreshResult = RefreshResult.HardExpired
        override suspend fun onAccountResolved(accountId: String) = Unit
        override suspend fun onAccountCleared() = Unit
        override fun emitSessionExpired(expiredAccountId: String?) = Unit
        override val sessionExpiredFlow: SharedFlow<Unit> = MutableSharedFlow()
    }

    private class FakeServerUrlProvider(private val baseUrl: String) : ServerUrlProvider {
        override fun getBaseUrl(): String = baseUrl
    }

    @Test
    fun `snapshot bearer is keyed to the snapshot account, not the live cache`() = runTest {
        // Sign-in staging in progress: the live cache holds the STAGED account's token, but the
        // resolved account A must snapshot its own keyed token.
        val gate = SwitchGate(
            activeAccountProvider = InMemoryActiveAccountProvider(Resolved(AccountId("acct-a"))),
            serverUrlProvider = FakeServerUrlProvider("https://a.example.com"),
            tokenManager = FakeTokenManager(liveToken = "staged-b", keyed = mapOf("acct-a" to "a-keyed")),
            accountReadyGate = null,
        )

        val snapshot = gate.captureSnapshot()

        assertThat(snapshot.accountId).isEqualTo("acct-a")
        assertThat(snapshot.bearer).isEqualTo("a-keyed")
        assertThat(snapshot.isPending).isFalse()
    }

    @Test
    fun `snapshot bearer is null for a resolved account with an empty keyed slot`() = runTest {
        // Must not fall through to the live cache (which may hold another account's staged token).
        val gate = SwitchGate(
            activeAccountProvider = InMemoryActiveAccountProvider(Resolved(AccountId("acct-a"))),
            serverUrlProvider = FakeServerUrlProvider("https://a.example.com"),
            tokenManager = FakeTokenManager(liveToken = "staged-b", keyed = emptyMap()),
            accountReadyGate = null,
        )

        assertThat(gate.captureSnapshot().bearer).isNull()
    }

    @Test
    fun `snapshot falls back to the live token when no account is resolved`() = runTest {
        // Legacy pre-migration / logged-out-with-bare-tokens path.
        val gate = SwitchGate(
            activeAccountProvider = InMemoryActiveAccountProvider(Resolved(null)),
            serverUrlProvider = FakeServerUrlProvider("https://a.example.com"),
            tokenManager = FakeTokenManager(liveToken = "bare-legacy"),
            accountReadyGate = null,
        )

        assertThat(gate.captureSnapshot().bearer).isEqualTo("bare-legacy")
    }

    @Test
    fun `pending identity short-circuits without reading the live providers`() = runTest {
        // Live providers that would fail the test if consulted.
        val gate = SwitchGate(
            activeAccountProvider = InMemoryActiveAccountProvider(Resolved(AccountId("acct-a"))),
            serverUrlProvider = object : ServerUrlProvider {
                override fun getBaseUrl(): String = error("live URL must not be read for a pending request")
            },
            tokenManager = object : TokenManager by FakeTokenManager(null) {
                override suspend fun getAccessToken(): String? = error("live token must not be read")
                override suspend fun getAccessTokenFor(accountId: String): String? = error("keyed token must not be read")
            },
            accountReadyGate = object : AccountReadyGate {
                override suspend fun awaitReady() = error("ready gate must not be awaited for a pending request")
            },
        )

        val snapshot = withContext(PendingRequestIdentity("https://b.example.com") { "staged-b" }) {
            gate.captureSnapshot()
        }

        assertThat(snapshot.baseUrl).isEqualTo("https://b.example.com")
        assertThat(snapshot.accountId).isNull()
        assertThat(snapshot.bearer).isEqualTo("staged-b")
        assertThat(snapshot.isPending).isTrue()
    }

    @Test
    fun `pending identity bypasses a closed switch gate`() = runTest {
        val gate = SwitchGate(
            activeAccountProvider = InMemoryActiveAccountProvider(Resolved(AccountId("acct-a"))),
            serverUrlProvider = FakeServerUrlProvider("https://a.example.com"),
            tokenManager = FakeTokenManager(liveToken = "a-token", keyed = mapOf("acct-a" to "a-token")),
            accountReadyGate = null,
        )
        val flipStarted = CompletableDeferred<Unit>()
        val releaseFlip = CompletableDeferred<Unit>()
        val switch = launch {
            gate.withSwitch {
                flipStarted.complete(Unit)
                releaseFlip.await()
            }
        }
        flipStarted.await()

        // A pending capture completes while the gate is closed; a live capture would park.
        val pending = async {
            withContext(PendingRequestIdentity("https://b.example.com") { null }) {
                gate.captureSnapshot()
            }
        }
        yield()
        assertThat(pending.isCompleted).isTrue()
        assertThat(pending.await().baseUrl).isEqualTo("https://b.example.com")

        releaseFlip.complete(Unit)
        switch.join()
    }
}
