package com.garfiec.librechat.core.data.repository

import com.garfiec.librechat.core.data.datastore.AccountRegistry
import com.garfiec.librechat.core.model.User
import com.garfiec.librechat.core.network.client.ServerUrlProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AccountSessionEstablisherTest {

    private val accountRegistry = mockk<AccountRegistry>(relaxed = true)
    private val claimReconciler = mockk<AccountClaimReconciler>(relaxed = true)
    private val serverUrlProvider = mockk<ServerUrlProvider>(relaxed = true)

    private val establisher = AccountSessionEstablisher(
        accountRegistry = accountRegistry,
        claimReconciler = claimReconciler,
        serverUrlProvider = serverUrlProvider,
        ioDispatcher = UnconfinedTestDispatcher(),
    )

    /**
     * Regression guard: the legacy claim must stamp NULL-accountId rows BEFORE the account is published.
     * If publish happened first, a sync in that window would overwrite still-unclaimed legacy rows and
     * drop their locally-stored tags (`upsertPreservingTags` reads `getByIdForAccount`, which misses a
     * NULL row).
     */
    @Test
    fun claimsLegacyRowsBeforePublishingAccount() = runTest {
        coEvery { serverUrlProvider.awaitBaseUrl() } returns "https://chat.example.com"
        val user = mockk<User>(relaxed = true)
        every { user.id } returns "mongoUserId"

        establisher.establish(user)

        coVerifyOrder {
            claimReconciler.claimIfNeeded(any(), "mongoUserId")
            accountRegistry.setActiveAccount(any())
        }
    }

    /**
     * The claim is best-effort: a failure must not strand the user at `Resolved(null)`. Even when the
     * claim throws, the account must still be published so scoped reads/writes go live (the claim
     * retries on the next establish).
     */
    @Test
    fun publishesAccountEvenWhenClaimFails() = runTest {
        coEvery { serverUrlProvider.awaitBaseUrl() } returns "https://chat.example.com"
        coEvery { claimReconciler.claimIfNeeded(any(), any()) } throws IllegalStateException("db error")
        val user = mockk<User>(relaxed = true)
        every { user.id } returns "mongoUserId"

        establisher.establish(user)

        coVerify { accountRegistry.setActiveAccount(any()) }
    }
}
