package com.garfiec.librechat.shared.navigation

import co.touchlab.kermit.Logger
import com.garfiec.librechat.core.common.identity.deriveServerId
import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.data.repository.BannerRepository
import com.garfiec.librechat.core.model.Banner
import com.garfiec.librechat.core.network.client.ServerUrlProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Holds the single server-configured banner. The server returns at most one and applies the
 * `displayFrom`/`displayTo` window itself, so a successful fetch simply replaces whatever is held.
 *
 * Dismissal is resolved here rather than in the UI, so "should this banner show" has one owner.
 * This holder is Activity-scoped and outlives account switches, so dismissals accumulate for the
 * process and are keyed by server. Web keeps the accumulating half of that invariant too
 * (`hideBannerHint` is an appended `string[]`).
 */
class BannerStateHolder(
    private val bannerRepository: BannerRepository,
    private val serverUrlProvider: ServerUrlProvider,
    private val scope: CoroutineScope,
) {

    // serverId the held banner came from, so a failed re-fetch can tell a banner carried over from
    // a PREVIOUS server (clear it — it describes someone else's deployment) apart from a transient
    // blip on the current one (keep it). Mirrors VersionCheckStateHolder.mismatchServerId.
    private var bannerServerId: String? = null

    private val _banner = MutableStateFlow<Banner?>(null)
    val banner: StateFlow<Banner?> = _banner.asStateFlow()

    // Dismissals, keyed by (server, banner) and never cleared for the process. Both halves of the
    // key are load-bearing and pull in opposite directions: keyed by banner alone, a fleet that
    // seeds one bannerId across its servers delivers the next server's banner pre-dismissed; wiped
    // on switch, dismissing A's banner and switching away and back brings A's straight back.
    private val dismissedKeys = mutableSetOf<String>()

    fun fetchBanner() {
        scope.launch {
            // awaitBaseUrl, not getBaseUrl: the init fetch runs inside the ViewModel constructor on
            // Main.immediate, before the persisted URL has resolved off "", and deriveServerId("")
            // throws — which would stamp a null id and silently disable the carry-over guard below.
            val serverId = runCatching { deriveServerId(serverUrlProvider.awaitBaseUrl()).value }
                .getOrNull()
            when (val result = bannerRepository.getBanner()) {
                is Result.Success -> {
                    bannerServerId = serverId
                    _banner.value = result.data?.takeUnless { it.isDismissed() }
                }
                is Result.Error -> {
                    if (bannerServerId != null && bannerServerId != serverId) {
                        clearBanner()
                    }
                    Logger.w(result.exception) { "Failed to fetch banner: ${result.message}" }
                }
                is Result.Loading -> Unit
            }
        }
    }

    /**
     * Hide the banner and remember it, so the next fetch of the same one on the same server does
     * not resurrect it. Dropping it here rather than filtering in the UI keeps the decision in one
     * place; [BannerDisplay] animates it out because it stays composed across the change.
     */
    fun dismissBanner(bannerId: String) {
        dismissedKeys += dismissKey(bannerServerId, bannerId)
        _banner.value = null
    }

    /**
     * The session this state described has ended (account switched away, or signed out). The banner
     * is scoped to a deployment, so it must not outlive it — the auth screens only hide it, and it
     * would otherwise reappear the moment the next login navigates. Dismissals are keyed by server
     * and deliberately survive: switching away and back must not un-dismiss anything.
     */
    fun clearForAccountChange() {
        clearBanner()
    }

    private fun Banner.isDismissed(): Boolean {
        val id = bannerId ?: return false
        // A persistable banner is one the server says may not be dismissed, so no key can exist.
        return persistable != true && dismissKey(bannerServerId, id) in dismissedKeys
    }

    private fun dismissKey(serverId: String?, bannerId: String): String =
        "${serverId.orEmpty()}\u0000$bannerId"

    private fun clearBanner() {
        _banner.value = null
        bannerServerId = null
    }
}
