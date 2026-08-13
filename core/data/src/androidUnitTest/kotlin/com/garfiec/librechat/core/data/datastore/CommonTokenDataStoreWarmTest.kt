package com.garfiec.librechat.core.data.datastore

import com.garfiec.librechat.core.network.client.SessionEndReason
import com.google.common.truth.Truth.assertThat
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The asynchronous token-cache warm: `TokenCacheWarmer` calls [CommonTokenDataStore.warmTokenCache] on
 * the IO dispatcher at startup, so the keystore work and the decrypt stay off the main thread. These
 * pin the properties that make that safe: the warm cannot revert a mutation, the synchronous fallback
 * still answers before the warm lands, and nothing is read from storage until one of the two happens.
 */
class CommonTokenDataStoreWarmTest {

    private fun store(
        seed: Map<String, String> = emptyMap(),
        warmEagerly: Boolean = false,
    ): FakeTokenStore = FakeTokenStore(
        refreshClient = lazy { error("refresh client not expected in this test") },
        seed = seed,
        warmEagerly = warmEagerly,
    )

    @Test
    fun construction_touchesStorage_zeroTimes() {
        val store = store(seed = bareSeed())

        assertThat(store.readCount).isEqualTo(0)
    }

    @Test
    fun warm_loadsTheActiveAccountAndItsBearer() = runTest {
        val store = store(
            seed = mapOf(
                ACTIVE_ACCOUNT_KEY to "acct-1",
                accessKeyOf("acct-1") to "keyed-access",
            ),
        )

        store.warmTokenCache()

        assertThat(store.getAccessToken()).isEqualTo("keyed-access")
    }

    @Test
    fun warm_isIdempotent_underConcurrency() = runTest {
        val store = store(seed = bareSeed())

        List(16) { async { store.warmTokenCache() } }.awaitAll()

        // loadCacheFromStorage performs exactly two reads: the mirror, then that account's access key.
        assertThat(store.readCount).isEqualTo(2)
    }

    @Test
    fun warmLandingAfterALogin_doesNotRevertIt() = runTest {
        // The clobber this design exists to prevent. setTokens holds stateMutex across its whole
        // critical section, so a warm arriving afterwards runs against storage the login has already
        // written through — it reloads the fresh pair rather than the stale disk state. (It is the
        // mutex that makes this safe, not tokenInitialized: only the load sets that flag.)
        val store = store(seed = bareSeed(access = "stale-on-disk", refresh = "stale-refresh"))

        store.setTokens(accessToken = "fresh-login", refreshToken = "fresh-refresh")
        store.warmTokenCache()

        assertThat(store.getAccessToken()).isEqualTo("fresh-login")
    }

    @Test
    fun warmLandingAfterAnAccountResolve_doesNotRevertTheIdentity() = runTest {
        val store = store(seed = bareSeed())

        store.setTokens(accessToken = "fresh-login", refreshToken = "fresh-refresh")
        store.onAccountResolved("acct-9")
        store.warmTokenCache()

        assertThat(store.getAccessToken()).isEqualTo("fresh-login")
        assertThat(store.store[accessKeyOf("acct-9")]).isEqualTo("fresh-login")
    }

    @Test
    fun warmRacingALogin_neverLosesTheLogin() = runTest {
        // Same property as above, but with the two genuinely interleaved rather than ordered.
        repeat(32) {
            val store = store(seed = bareSeed(access = "stale-on-disk", refresh = "stale-refresh"))

            val warm = launch { store.warmTokenCache() }
            val login = launch { store.setTokens(accessToken = "fresh-login", refreshToken = "fresh") }
            warm.join()
            login.join()

            assertThat(store.getAccessToken()).isEqualTo("fresh-login")
        }
    }

    @Test
    fun synchronousFallback_answersCorrectly_withNoWarmAtAll() {
        val loggedIn = store(seed = bareSeed(access = "on-disk", refresh = "r"))
        assertThat(loggedIn.isAuthenticated).isTrue()

        val loggedOut = store(seed = emptyMap())
        assertThat(loggedOut.isAuthenticated).isFalse()
    }

    @Test
    fun synchronousFallback_readsThrough_untilTheWarmLands() = runTest {
        val store = store(seed = bareSeed())

        // Read-through: two decrypts per call, publishing nothing.
        repeat(3) { store.isAuthenticated }
        assertThat(store.readCount).isEqualTo(6)

        store.warmTokenCache()
        val afterWarm = store.readCount

        // Once the warm has published, the fallback is never taken again.
        repeat(3) { store.isAuthenticated }
        assertThat(store.readCount).isEqualTo(afterWarm)
    }

    @Test
    fun synchronousFallback_publishesNothing_soItCannotPinAStaleBearer() = runTest {
        // If the fallback populated the cache fields, a reader that began before setTokens wrote and
        // assigned after would overwrite the fresh bearer with the pre-login disk value AND set
        // tokenInitialized, pinning it for the life of the process.
        val store = store(seed = bareSeed(access = "stale-on-disk", refresh = "stale-refresh"))

        // Take the fallback path first, so anything it might have published is already in place.
        assertThat(store.isAuthenticated).isTrue()
        store.setTokens(accessToken = "fresh-login", refreshToken = "fresh-refresh")

        assertThat(store.getAccessToken()).isEqualTo("fresh-login")
        store.warmTokenCache()
        assertThat(store.getAccessToken()).isEqualTo("fresh-login")
    }

    @Test
    fun emitSessionExpired_onAnUnseededStore_stillEmitsForTheActiveAccount() = runTest {
        // Unseeded, activeAccountKey is null, so comparing against it would suppress every scoped emit
        // — swallowing the signal and stranding the user on a dead session.
        val store = store(seed = mapOf(ACTIVE_ACCOUNT_KEY to "acct-1"))
        val received = async { store.sessionExpiredFlow.first() }
        // Let the collector subscribe; emitSessionExpired drops the signal when nobody is listening.
        kotlinx.coroutines.yield()

        store.emitSessionExpired("acct-1", SessionEndReason.EXPIRED)

        assertThat(received.await()).isEqualTo(SessionEndReason.EXPIRED)
    }

    @Test
    fun emitSessionExpired_onAnUnseededStore_stillSuppressesARetainedAccount() = runTest {
        // The other half: skipping the comparison while unseeded lets a straggler 401 for a
        // switched-away account through, and sessionExpiryReported is one-shot per session — so that
        // spurious emit latches and swallows the ACTIVE account's real expiry.
        val store = store(seed = mapOf(ACTIVE_ACCOUNT_KEY to "acct-1"))
        var emitted = false
        val collector = launch { store.sessionExpiredFlow.first(); emitted = true }
        kotlinx.coroutines.yield()

        store.emitSessionExpired("acct-retained", SessionEndReason.EXPIRED)
        kotlinx.coroutines.yield()

        assertThat(emitted).isFalse()
        collector.cancel()
    }

    @Test
    fun emitSessionExpired_afterWarm_stillSuppressesAnInactiveAccount() = runTest {
        // Only the ACTIVE binding's expiry routes to re-auth.
        val store = store(seed = mapOf(ACTIVE_ACCOUNT_KEY to "acct-1"))
        store.warmTokenCache()
        var emitted = false
        val collector = launch { store.sessionExpiredFlow.first(); emitted = true }
        kotlinx.coroutines.yield()

        store.emitSessionExpired("acct-other", SessionEndReason.EXPIRED)
        kotlinx.coroutines.yield()

        assertThat(emitted).isFalse()
        collector.cancel()
    }

    @Test
    fun warmer_survivesAStoreWhoseStorageThrows() = runTest {
        // A broken keystore must degrade to the synchronous fallback, not take down the application
        // scope the warmer launches on — every other startup coroutine shares it.
        val store = object : CommonTokenDataStore(
            refreshClient = lazy { error("refresh client not expected in this test") },
            ioDispatcher = Dispatchers.Unconfined,
        ) {
            override fun readValue(key: String): String? = error("keystore is broken")
            override fun writeValue(key: String, value: String) = Unit
            override fun writeValues(values: Map<String, String>) = Unit
            override fun removeValue(key: String) = Unit
            override fun onKeystoreCorruption() = Unit
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

        TokenCacheWarmer(store = store, appScope = scope)

        assertThat(scope.isActive).isTrue()
        scope.cancel()
    }
}
