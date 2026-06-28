package com.garfiec.librechat.core.common.identity

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SessionManagerTest {

    @Test
    fun noSessionWhileWarming() = runTest {
        val provider = InMemoryActiveAccountProvider() // starts Warming
        val manager = SessionManager(provider, backgroundScope)
        runCurrent()
        assertNull(manager.current.value, "Warming must not form a session")
    }

    @Test
    fun noSessionWhenLoggedOut() = runTest {
        val provider = InMemoryActiveAccountProvider()
        val manager = SessionManager(provider, backgroundScope)
        provider.clear() // Resolved(null)
        runCurrent()
        assertNull(manager.current.value, "Resolved(null) (logged-out) must not form a session")
    }

    @Test
    fun formsSessionOnResolvedId() = runTest {
        val provider = InMemoryActiveAccountProvider()
        val manager = SessionManager(provider, backgroundScope)
        provider.set(AccountId("acct-A"))
        runCurrent()
        assertEquals(AccountId("acct-A"), manager.current.value?.accountId)
        assertTrue(manager.current.value!!.scope.isActive)
    }

    @Test
    fun flipToNullEndsSession_andCancelsItsScope() = runTest {
        val provider = InMemoryActiveAccountProvider()
        val manager = SessionManager(provider, backgroundScope)
        provider.set(AccountId("acct-A"))
        runCurrent()

        val sessionA = manager.current.value!!
        var aCancelled = false
        sessionA.scope.launch { try { awaitCancellation() } finally { aCancelled = true } }
        runCurrent()

        provider.clear()
        runCurrent()

        assertNull(manager.current.value)
        assertFalse(sessionA.scope.isActive, "old session scope must be cancelled")
        assertTrue(aCancelled, "in-flight account-scoped work must be cancelled on logout")
    }

    @Test
    fun replacesSessionOnIdChange_cancelsOldScope() = runTest {
        val provider = InMemoryActiveAccountProvider()
        val manager = SessionManager(provider, backgroundScope)
        provider.set(AccountId("acct-A"))
        runCurrent()
        val sessionA = manager.current.value!!

        // Login-as-B replaces A; A's scope (and anything launched in it) is torn down.
        provider.set(AccountId("acct-B"))
        runCurrent()

        val sessionB = manager.current.value!!
        assertEquals(AccountId("acct-B"), sessionB.accountId)
        assertFalse(sessionA.scope.isActive)
        assertTrue(sessionB.scope.isActive)
    }

    @Test
    fun reEmittingSameId_keepsSameSession() = runTest {
        val provider = InMemoryActiveAccountProvider()
        val manager = SessionManager(provider, backgroundScope)
        provider.set(AccountId("acct-A"))
        runCurrent()
        val first = manager.current.value!!

        // A redundant resolution of the same id must not churn the live session.
        provider.set(AccountId("acct-A"))
        runCurrent()
        assertSame(first, manager.current.value)
    }

    @Test
    fun endCurrentSession_tearsDownAndLeavesNull() = runTest {
        val provider = InMemoryActiveAccountProvider()
        val manager = SessionManager(provider, backgroundScope)
        provider.set(AccountId("acct-A"))
        runCurrent()
        val sessionA = manager.current.value!!

        manager.endCurrentSession()

        assertNull(manager.current.value)
        assertFalse(sessionA.scope.isActive)
    }
}
