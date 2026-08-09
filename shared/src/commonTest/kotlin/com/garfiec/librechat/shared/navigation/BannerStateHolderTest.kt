package com.garfiec.librechat.shared.navigation

import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.data.repository.BannerRepository
import com.garfiec.librechat.core.model.Banner
import com.garfiec.librechat.core.network.client.ServerUrlProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BannerStateHolderTest {

    private class FakeBannerRepository(var result: Result<Banner?>) : BannerRepository {
        override suspend fun getBanner(): Result<Banner?> = result
    }

    private class FakeServerUrlProvider(var url: String = "https://one.example") : ServerUrlProvider {
        override fun getBaseUrl(): String = url
    }

    /** Unconfined so [BannerStateHolder.fetchBanner]'s launch runs to completion inline. */
    private fun holderFor(
        result: Result<Banner?>,
        serverUrlProvider: ServerUrlProvider = FakeServerUrlProvider(),
    ): Pair<BannerStateHolder, FakeBannerRepository> {
        val repository = FakeBannerRepository(result)
        val holder =
            BannerStateHolder(repository, serverUrlProvider, CoroutineScope(Dispatchers.Unconfined))
        return holder to repository
    }

    private fun banner(id: String = "b1") = Banner(bannerId = id, message = "hello")

    @Test
    fun fetch_publishesTheBanner() {
        val (holder, _) = holderFor(Result.Success(banner()))
        holder.fetchBanner()
        assertEquals("b1", holder.banner.value?.bannerId)
    }

    @Test
    fun fetch_clearsWhenTheServerHasNoBanner() {
        val (holder, repository) = holderFor(Result.Success(banner()))
        holder.fetchBanner()
        // e.g. switching to an account on a server with no banner configured.
        repository.result = Result.Success(null)
        holder.fetchBanner()
        assertNull(holder.banner.value)
    }

    @Test
    fun fetch_keepsTheBannerWhenTheSameServerFails() {
        // A blip on the server that sent it is no reason to hide a banner it chose to send.
        val (holder, repository) = holderFor(Result.Success(banner()))
        holder.fetchBanner()
        repository.result = Result.Error(RuntimeException("boom"), "boom")
        holder.fetchBanner()
        assertEquals("b1", holder.banner.value?.bannerId)
    }

    @Test
    fun fetch_clearsACarriedOverBannerWhenANewServerFails() {
        // Switching accounts to an unreachable server must not leave the previous server's banner
        // on screen — it describes a deployment the user is no longer looking at.
        val serverUrlProvider = FakeServerUrlProvider()
        val (holder, repository) = holderFor(Result.Success(banner()), serverUrlProvider)
        holder.fetchBanner()
        serverUrlProvider.url = "https://two.example"
        repository.result = Result.Error(RuntimeException("unreachable"), "unreachable")
        holder.fetchBanner()
        assertNull(holder.banner.value)
    }

    @Test
    fun dismiss_hidesTheBannerAndSurvivesARefetchOfTheSameOne() {
        val (holder, _) = holderFor(Result.Success(banner()))
        holder.fetchBanner()
        holder.dismissBanner("b1")
        assertNull(holder.banner.value)
        // onAuthComplete / a later switch re-fetches the identical banner; it must stay hidden.
        holder.fetchBanner()
        assertNull(holder.banner.value)
    }

    @Test
    fun dismiss_survivesSwitchingAwayAndBack() {
        // Dismiss A's banner, switch to B, switch back: A's must not return. Wiping dismissals on
        // switch (or keying them by banner id alone) breaks exactly this.
        val serverUrlProvider = FakeServerUrlProvider()
        val (holder, repository) = holderFor(Result.Success(banner()), serverUrlProvider)
        holder.fetchBanner()
        holder.dismissBanner("b1")

        serverUrlProvider.url = "https://two.example"
        holder.clearForAccountChange()
        repository.result = Result.Success(banner("b2"))
        holder.fetchBanner()
        assertEquals("b2", holder.banner.value?.bannerId)

        serverUrlProvider.url = "https://one.example"
        holder.clearForAccountChange()
        repository.result = Result.Success(banner("b1"))
        holder.fetchBanner()
        assertNull(holder.banner.value)
    }

    @Test
    fun dismiss_isScopedToTheServerThatSentTheBanner() {
        // A fleet seeding one bannerId across its servers must not deliver B's pre-dismissed.
        val serverUrlProvider = FakeServerUrlProvider()
        val (holder, _) = holderFor(Result.Success(banner("shared-id")), serverUrlProvider)
        holder.fetchBanner()
        holder.dismissBanner("shared-id")

        serverUrlProvider.url = "https://two.example"
        holder.clearForAccountChange()
        holder.fetchBanner()
        assertEquals("shared-id", holder.banner.value?.bannerId)
    }

    @Test
    fun dismiss_isIgnoredForAPersistableBanner() {
        // The server says this one may not be dismissed, so a stray call must not hide it.
        val persistable = Banner(bannerId = "b1", message = "hello", persistable = true)
        val (holder, _) = holderFor(Result.Success(persistable))
        holder.fetchBanner()
        holder.dismissBanner("b1")
        holder.fetchBanner()
        assertEquals("b1", holder.banner.value?.bannerId)
    }

    @Test
    fun clearForAccountChange_dropsTheBanner() {
        val (holder, _) = holderFor(Result.Success(banner()))
        holder.fetchBanner()
        holder.clearForAccountChange()
        assertNull(holder.banner.value)
    }

    @Test
    fun clearForAccountChange_alsoDropsTheServerStampSoALaterFailureCannotResurrect() {
        val (holder, repository) = holderFor(Result.Success(banner()))
        holder.fetchBanner()
        holder.clearForAccountChange()
        repository.result = Result.Error(RuntimeException("unreachable"), "unreachable")
        holder.fetchBanner()
        assertNull(holder.banner.value)
    }
}
