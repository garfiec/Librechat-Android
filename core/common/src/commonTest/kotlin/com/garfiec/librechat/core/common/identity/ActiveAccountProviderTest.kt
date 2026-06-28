package com.garfiec.librechat.core.common.identity

import com.garfiec.librechat.core.common.identity.AccountState.Resolved
import com.garfiec.librechat.core.common.identity.AccountState.Warming
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ActiveAccountProviderTest {

    @Test
    fun startsWarming() {
        assertTrue(InMemoryActiveAccountProvider().state.value is Warming)
    }

    @Test
    fun setMovesToResolvedWithLiveId() {
        val provider = InMemoryActiveAccountProvider()
        provider.set(AccountId("acct-A"))
        assertEquals(Resolved(AccountId("acct-A")), provider.state.value)
    }

    @Test
    fun clearMovesToResolvedNull_notBackToWarming() {
        val provider = InMemoryActiveAccountProvider()
        provider.set(AccountId("acct-A"))
        provider.clear()
        // Logged-out is Resolved(null), a known "nobody" — NOT Warming.
        assertEquals(Resolved(null), provider.state.value)
    }

    @Test
    fun awaitResolvedAccount_skipsWarmingAndLoggedOut_thenReturnsLiveId() = runTest {
        val provider = InMemoryActiveAccountProvider()

        val resolved = async { provider.awaitResolvedAccount() }
        runCurrent()
        // Warming must not satisfy the gate.
        assertTrue(resolved.isActive)

        provider.clear()
        runCurrent()
        // Resolved(null) (logged-out) must not satisfy the gate either.
        assertTrue(resolved.isActive)

        provider.set(AccountId("acct-A"))
        assertEquals(AccountId("acct-A"), resolved.await())
    }

    @Test
    fun awaitResolvedAccount_isReEvaluablePerCall_observesLatestId() = runTest {
        val provider = InMemoryActiveAccountProvider()
        provider.set(AccountId("acct-A"))
        assertEquals(AccountId("acct-A"), provider.awaitResolvedAccount())

        // A later epoch (logout then login-as-B) resolves the new id, not the cached first one.
        provider.clear()
        provider.set(AccountId("acct-B"))
        assertEquals(AccountId("acct-B"), provider.awaitResolvedAccount())
    }

    @Test
    fun accountIdRejectsBlank() {
        assertFailsWith<IllegalArgumentException> { AccountId("  ") }
    }
}
