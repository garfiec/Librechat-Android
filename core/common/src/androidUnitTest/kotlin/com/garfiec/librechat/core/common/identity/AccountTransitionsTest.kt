package com.garfiec.librechat.core.common.identity

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class AccountTransitionsTest {

    private val accountA = AccountId("srv-1:user-a")
    private val accountB = AccountId("srv-1:user-b")

    @Test
    fun `cold start resolution never emits`() = runTest {
        val provider = InMemoryActiveAccountProvider(AccountState.Warming)
        provider.accountTransitions().test {
            provider.set(accountA) // Warming -> first Resolved: the seed, not a transition
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `account to account flip emits Switched`() = runTest {
        val provider = InMemoryActiveAccountProvider(AccountState.Resolved(accountA))
        provider.accountTransitions().test {
            provider.set(accountB)
            assertThat(awaitItem()).isEqualTo(AccountTransition.Switched)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `account to logged-out flip emits Ended`() = runTest {
        val provider = InMemoryActiveAccountProvider(AccountState.Resolved(accountA))
        provider.accountTransitions().test {
            provider.clear()
            assertThat(awaitItem()).isEqualTo(AccountTransition.Ended)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `logout then login as another account emits Ended only`() = runTest {
        val provider = InMemoryActiveAccountProvider(AccountState.Resolved(accountA))
        provider.accountTransitions().test {
            provider.clear()
            assertThat(awaitItem()).isEqualTo(AccountTransition.Ended)
            provider.set(accountB) // login from logged-out: auth flow owns this navigation
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }
}
