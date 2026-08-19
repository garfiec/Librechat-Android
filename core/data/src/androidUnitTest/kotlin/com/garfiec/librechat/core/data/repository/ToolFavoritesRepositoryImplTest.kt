package com.garfiec.librechat.core.data.repository

import com.garfiec.librechat.core.common.BackendBuildClass
import com.garfiec.librechat.core.common.DetectedBackend
import com.garfiec.librechat.core.common.result.ApiException
import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.model.ToolFavorite
import com.garfiec.librechat.core.model.ToolFavoriteItemType
import com.garfiec.librechat.core.network.api.FavoritesApi
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ToolFavoritesRepositoryImplTest {

    private val api = mockk<FavoritesApi>(relaxUnitFun = true)
    private val configRepository = mockk<ConfigRepository>()

    private fun repository(detected: DetectedBackend?): ToolFavoritesRepository {
        every { configRepository.detectedBackend } returns MutableStateFlow(detected)
        return ToolFavoritesRepositoryImpl(api, configRepository)
    }

    private fun rcBackend() =
        DetectedBackend("0.8.8-rc1", BackendBuildClass.RC, "2026-08-14")

    @Test
    fun `an rc1 server probes the routes`() = runTest {
        coEvery { api.getToolFavorites() } returns
            listOf(ToolFavorite(ToolFavoriteItemType.MCP, "jira"))

        val repository = repository(rcBackend())
        repository.refresh()

        assertThat(repository.isSupported.value).isTrue()
        assertThat(repository.favorites.value.map { it.itemKey }).containsExactly("mcp:jira")
    }

    @Test
    fun `a tagged build below the routes never calls them`() = runTest {
        // An OFFICIAL build reports the version of its tag, so falling short of 0.8.8-rc1 is
        // proof rather than evidence — nothing to discover, and no request worth spending.
        val repository = repository(DetectedBackend("0.8.7", BackendBuildClass.OFFICIAL, "2026-06-26"))

        val result = repository.refresh()

        assertThat(result).isInstanceOf(Result.Success::class.java)
        assertThat(repository.isSupported.value).isFalse()
        coVerify(exactly = 0) { api.getToolFavorites() }
    }

    @Test
    fun `a dev build reporting the previous release is asked rather than assumed`() = runTest {
        // Upstream bumps package.json at rc prep, so a 0.8.8-cycle dev build still says "0.8.7".
        // A plain version compare read that as "too old" and rendered the picker with no star
        // column at all on the self-hosted servers most likely to have the routes.
        coEvery { api.getToolFavorites() } returns
            listOf(ToolFavorite(ToolFavoriteItemType.MCP, "jira"))
        val repository = repository(DetectedBackend("0.8.7", BackendBuildClass.DEV, "2026-07-04"))

        repository.refresh()

        assertThat(repository.isSupported.value).isTrue()
        coVerify(exactly = 1) { api.getToolFavorites() }
    }

    @Test
    fun `a server with no resolvable build commit is asked too`() = runTest {
        // Null detection is dominated by servers built PAST the app's commit-map pin — newer
        // than us, not older.
        coEvery { api.getToolFavorites() } returns emptyList()
        val repository = repository(null)

        repository.refresh()

        assertThat(repository.isSupported.value).isTrue()
        coVerify(exactly = 1) { api.getToolFavorites() }
    }

    @Test
    fun `a 404 is asked once, not on every picker open`() = runTest {
        coEvery { api.getToolFavorites() } throws ApiException(404, "Not Found")
        val repository = repository(null)

        repository.refresh()
        repository.refresh()
        repository.refresh()

        // The route answered definitively the first time. isSupported cannot carry that verdict —
        // it also reads false before any probe — so re-asking is what happens without the latch.
        assertThat(repository.isSupported.value).isFalse()
        coVerify(exactly = 1) { api.getToolFavorites() }
    }

    @Test
    fun `the next server gets its own probe`() = runTest {
        coEvery { api.getToolFavorites() } throws ApiException(404, "Not Found")
        val repository = repository(null)
        repository.refresh()

        repository.clear()
        coEvery { api.getToolFavorites() } returns emptyList()
        repository.refresh()

        // Without the reset in clear(), one server's 404 would decide for every account switched
        // to afterwards — the repository is an app-wide singleton.
        assertThat(repository.isSupported.value).isTrue()
        coVerify(exactly = 2) { api.getToolFavorites() }
    }

    @Test
    fun `a 404 turns pinning off rather than reporting a failure to the user`() = runTest {
        coEvery { api.getToolFavorites() } throws ApiException(404, "Not Found")

        val repository = repository(rcBackend())
        repository.refresh()

        assertThat(repository.isSupported.value).isFalse()
    }

    @Test
    fun `a transient failure leaves support alone`() = runTest {
        coEvery { api.getToolFavorites() } returns emptyList()
        val repository = repository(rcBackend())
        repository.refresh()
        assertThat(repository.isSupported.value).isTrue()

        // A 500 says nothing about whether the route exists, so it must not undo the discovery
        // a successful probe already made.
        coEvery { api.getToolFavorites() } throws ApiException(500, "Server error")
        repository.refresh()

        assertThat(repository.isSupported.value).isTrue()
    }

    @Test
    fun `a failed pin rolls the optimistic star back`() = runTest {
        coEvery { api.addToolFavorite(any(), any()) } throws ApiException(500, "Server error")
        val repository = repository(rcBackend())

        val result = repository.toggle(ToolFavoriteItemType.TOOL, "wolfram")

        assertThat(result).isInstanceOf(Result.Error::class.java)
        assertThat(repository.favorites.value).isEmpty()
    }

    @Test
    fun `a second toggle unpins`() = runTest {
        val repository = repository(rcBackend())

        repository.toggle(ToolFavoriteItemType.TOOL, "wolfram")
        assertThat(repository.favorites.value.map { it.itemKey }).containsExactly("tool:wolfram")

        repository.toggle(ToolFavoriteItemType.TOOL, "wolfram")

        assertThat(repository.favorites.value).isEmpty()
        coVerify(exactly = 1) { api.removeToolFavorite(ToolFavoriteItemType.TOOL, "wolfram") }
    }

    @Test
    fun `clear drops both the pins and the discovered support`() = runTest {
        coEvery { api.getToolFavorites() } returns
            listOf(ToolFavorite(ToolFavoriteItemType.MCP, "jira"))
        val repository = repository(rcBackend())
        repository.refresh()

        repository.clear()

        // The account-switch path relies on this: refresh() keeps the old set on a non-404 error,
        // so the incoming account would otherwise inherit the outgoing one's pins.
        assertThat(repository.favorites.value).isEmpty()
        assertThat(repository.isSupported.value).isFalse()
    }

    @Test
    fun `the cap is enforced before the write, not after the server rejects it`() = runTest {
        val repository = repository(rcBackend())
        repeat(100) { repository.toggle(ToolFavoriteItemType.TOOL, "tool-$it") }

        val result = repository.toggle(ToolFavoriteItemType.TOOL, "one-too-many")

        assertThat(result).isInstanceOf(Result.Error::class.java)
        assertThat(repository.favorites.value).hasSize(100)
        coVerify(exactly = 0) { api.addToolFavorite(any(), "one-too-many") }
    }
}
