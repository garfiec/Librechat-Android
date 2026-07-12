package com.garfiec.librechat.shared.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import com.garfiec.librechat.core.common.extensions.serverHostLabel
import com.garfiec.librechat.core.common.identity.AccountState
import com.garfiec.librechat.core.common.identity.AccountTransition
import com.garfiec.librechat.core.common.identity.ActiveAccountProvider
import com.garfiec.librechat.core.common.identity.accountTransitions
import com.garfiec.librechat.core.common.network.ConnectivityObserver
import com.garfiec.librechat.core.data.datastore.AccountEntry
import com.garfiec.librechat.core.data.datastore.AccountRoster
import com.garfiec.librechat.core.data.datastore.SettingsDataStore
import com.garfiec.librechat.core.data.repository.AccountSwitcher
import com.garfiec.librechat.core.data.repository.AuthRepository
import com.garfiec.librechat.core.data.repository.BannerRepository
import com.garfiec.librechat.core.data.repository.ConfigRepository
import com.garfiec.librechat.core.data.repository.EndpointTokenRepository
import com.garfiec.librechat.core.data.util.SessionTaskRunner
import com.garfiec.librechat.core.model.Banner
import com.garfiec.librechat.core.network.client.AccountReadyGate
import com.garfiec.librechat.core.network.client.ServerUrlProvider
import com.garfiec.librechat.core.network.client.TokenManager
import com.garfiec.librechat.feature.conversations.drawer.AccountUiModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Navigation-shell ViewModel: auth/session routing, account identity + hygiene, banners, version
 * check, and sidebar mode. The drawer's conversation-list data lives in [com.garfiec.librechat
 * .feature.conversations.drawer.DrawerViewModel] (extracted so `:shared` is nav glue, not a stealth
 * feature module); this VM keeps the account *list* the drawer footer renders, but not the drawer data.
 */
class NavHostViewModel(
    private val authRepository: AuthRepository,
    bannerRepository: BannerRepository,
    private val configRepository: ConfigRepository,
    private val sessionTaskRunner: SessionTaskRunner,
    private val tokenManager: TokenManager,
    private val accountReadyGate: AccountReadyGate,
    private val settingsDataStore: SettingsDataStore,
    private val serverUrlProvider: ServerUrlProvider,
    private val connectivityObserver: ConnectivityObserver,
    private val endpointTokenRepository: EndpointTokenRepository,
    private val activeAccountProvider: ActiveAccountProvider,
    private val accountRoster: AccountRoster,
    private val accountSwitcher: AccountSwitcher,
) : ViewModel() {

    private val bannerStateHolder = BannerStateHolder(bannerRepository, viewModelScope)
    private val versionCheckStateHolder =
        VersionCheckStateHolder(configRepository, settingsDataStore, serverUrlProvider, viewModelScope)

    // Seeded synchronously so first-frame routing (LibreChatNavHost reads isLoggedIn.value
    // once in a LaunchedEffect to redirect to auth) gets the correct value with no flash.
    // This is a non-blocking in-memory cache read: TokenManager decrypts the access token at
    // its own construction (TokenDataStore.init -> initializeTokenCache), so by the time the
    // VM is built the token is already cached and isAuthenticated is just a null check. The
    // init{} block below re-resolves the same value asynchronously. (The Keychain/
    // EncryptedSharedPreferences decrypt itself still runs on Main at TokenDataStore
    // construction — see report follow-up; it is out of this stream's three files.)
    private val _isLoggedIn = MutableStateFlow(tokenManager.isAuthenticated)
    val isLoggedIn: StateFlow<Boolean> = _isLoggedIn.asStateFlow()

    val versionMismatch: StateFlow<VersionMismatchState?> = versionCheckStateHolder.versionMismatch

    val banners: StateFlow<List<Banner>> = bannerStateHolder.banners
    val dismissedBannerIds: StateFlow<Set<String>> = bannerStateHolder.dismissedBannerIds

    val sessionExpired: SharedFlow<Unit> = tokenManager.sessionExpiredFlow

    // The live identity for the NavHost's account hygiene (Coil cache clear + back-stack reset on
    // an account flip). Exposed as STATE rather than a transition flow so the UI can persist the
    // identity it last ran hygiene for and catch up after Activity recreation or process death — a
    // cold transition flow restarted in that gap would silently swallow a flip that landed mid-gap.
    val accountState: StateFlow<AccountState> = activeAccountProvider.state

    // The signed-in accounts for the drawer chip + switcher sheet: active entry first, the rest by
    // recency (matching "switch to most-recently-active" on remove). Account identity + switching
    // stay in the nav shell; only the drawer *renders* this list ([AccountUiModel] lives with the
    // drawer feature, hence the cross-module type reference).
    val accounts: StateFlow<List<AccountUiModel>> =
        combine(accountRoster.entriesFlow(), activeAccountProvider.state) { entries, accountState ->
            val activeId = (accountState as? AccountState.Resolved)?.id?.value
            entries
                .sortedWith(
                    compareByDescending<AccountEntry> { it.accountId == activeId }
                        .thenByDescending { it.lastActiveAt },
                )
                .map { entry ->
                    AccountUiModel(
                        accountId = entry.accountId,
                        displayLabel = entry.displayLabel,
                        serverHost = entry.serverUrl.serverHostLabel(),
                        avatarUrl = entry.avatarUrl,
                        isActive = entry.accountId == activeId,
                    )
                }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Seeded `null` (= "not resolved yet") and warmed up by the Eagerly-started collector. The
    // previous synchronous `firstBlocking` read blocked the Main thread Koin instantiates the VM
    // on. The nullable seed lets TabletLayout distinguish "unknown" from "closed" so it can snap
    // to the persisted state on first resolution instead of animating false -> true (a visible
    // sidebar jump on every tablet cold start).
    val tabletSidebarOpen: StateFlow<Boolean?> = settingsDataStore.tabletSidebarOpen
        .map<Boolean, Boolean?> { it }
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            null,
        )

    val tabletSidebarGestureEnabled: StateFlow<Boolean> = settingsDataStore.tabletSidebarGestureEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    private val _sidebarMode = MutableStateFlow<SidebarMode>(SidebarMode.Conversations)
    val sidebarMode: StateFlow<SidebarMode> = _sidebarMode.asStateFlow()

    private val _selectedSettingsCategory = MutableStateFlow<SettingsCategory?>(null)
    val selectedSettingsCategory: StateFlow<SettingsCategory?> = _selectedSettingsCategory.asStateFlow()

    fun setSidebarMode(mode: SidebarMode) {
        _sidebarMode.value = mode
    }

    fun selectSettingsCategory(category: SettingsCategory) {
        _selectedSettingsCategory.value = category
    }

    init {
        viewModelScope.launch {
            try {
                // Wait for the server URL's async warm-up before any startup network work, so a
                // logged-in cold start can't fire requests (auth check, version/config fetch,
                // session tasks) at an empty base URL while ServerDataStore is still resolving.
                serverUrlProvider.awaitBaseUrl()
                // Wait for the roster seed to reconcile the token mirror to the durable active pointer
                // before deciding the route. The synchronous _isLoggedIn seed above reads the raw
                // (possibly crash-diverged) mirror; without this gate a divergence would flash the
                // wrong screen. The gate's seed also drives the server URL, so this is ordered first.
                accountReadyGate.awaitReady()
                val loggedIn = authRepository.isLoggedIn()
                _isLoggedIn.value = loggedIn
                if (loggedIn) {
                    // Upgrade safety net: establish the active account before any tenant reads/writes
                    // when an already-logged-in user came from a pre-tenancy build (no login fires).
                    // Isolate its failure: restore does a live getUser() on the upgrade path, and a
                    // transient error (e.g. offline first launch) must not also skip the version check
                    // and session tasks below — those are independent of account resolution.
                    val accountResolved = tryRestoreAccount()
                    versionCheckStateHolder.checkBackendVersion()
                    // Session tasks for the cold-start case (role fetch, tag refresh,
                    // favorites sync). Runs on the application scope so tasks outlive
                    // this VM's scope. Fresh logins fire these from AuthRepositoryImpl.
                    sessionTaskRunner.runAll()
                    // Offline-upgrade recovery: getUser() couldn't run, so the account is unresolved and
                    // every tenant read is empty for an otherwise logged-in user. Re-attempt when
                    // connectivity returns instead of stranding them until a manual relaunch.
                    if (!accountResolved) retryAccountRestoreOnReconnect()
                }
            } catch (e: Exception) {
                Logger.w(e) { "Failed to check auth state on init" }
            }
        }
        bannerStateHolder.fetchBanners()
        // The active account changed underneath this Activity-scoped VM (switch / add-completion /
        // remove → Switched; remove-last → Ended; plain logout also lands here as Ended, where the
        // clears below just repeat logout()'s — idempotent). This is the nav/session half of the
        // reset; the drawer half (conversation list, tags, projects) runs in DrawerViewModel against
        // its own independent accountTransitions() subscription.
        viewModelScope.launch {
            activeAccountProvider.accountTransitions().collect { transition ->
                _sidebarMode.value = SidebarMode.Conversations
                _selectedSettingsCategory.value = null
                endpointTokenRepository.clear()
                // Reseed the in-memory config from the (already-flipped) server's own srv:-keyed
                // cache — warm on switch-back — instead of clear(), which would wipe every server's
                // disk cache.
                configRepository.reloadForActiveServer()
                if (transition is AccountTransition.Switched) {
                    // The switch path never runs the login-side session machinery
                    // (AuthRepositoryImpl fires these on sign-in; logout/re-auth via onAuthComplete)
                    // so the incoming account's session state is fetched here.
                    bannerStateHolder.fetchBanners()
                    versionCheckStateHolder.checkBackendVersion()
                    sessionTaskRunner.runAll()
                } else if (transition is AccountTransition.Ended) {
                    // The last account signed out (plain logout, or remove-last): mark logged-out so
                    // first-frame routing is correct. Nav-to-auth itself rides the session-expired
                    // signal emitted by the teardown.
                    _isLoggedIn.value = false
                }
            }
        }
    }

    /** Switch the active account (no-op for the already-active one). Post-switch refresh and the
     *  back-stack reset ride on the [accountTransitions] collectors, not this call. */
    fun switchAccount(accountId: String) {
        viewModelScope.launch { accountSwitcher.switch(accountId) }
    }

    /** Remove an account and all its local data. Removing the active one switches to the
     *  most-recently-active survivor, or routes to auth when it was the last. */
    fun removeAccount(accountId: String) {
        viewModelScope.launch { accountSwitcher.remove(accountId) }
    }

    /** Abandon any in-progress add-account flow; no-op when none is pending. Driven by the NavHost
     *  when the add-flow routes leave the back stack (back-out, session expiry, completion). */
    fun cancelPendingAdd() {
        viewModelScope.launch { accountSwitcher.cancelAdd() }
    }

    /** True while an add-account flow is in progress. The NavHost checks this at composition: a
     *  back stack restored after process death can still hold add-flow routes, but the pending
     *  session is memory-only — restored add-mode screens without it would silently target the
     *  LIVE server, so the NavHost strips them. */
    fun hasPendingAdd(): Boolean = accountSwitcher.pendingAdd != null

    /**
     * Attempts the upgrade-path account restore, swallowing transient failures. Returns `true` when the
     * account is resolved (or restore wasn't needed), `false` when a logged-in upgrade user is still
     * unaccounted and should be retried on reconnect. restoreAccountIfNeeded() self-guards, so it is
     * cheap and safe to call repeatedly.
     */
    private suspend fun tryRestoreAccount(): Boolean =
        try {
            authRepository.restoreAccountIfNeeded()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(e) { "Account restore failed on cold start; will retry on reconnect" }
            false
        }

    private fun retryAccountRestoreOnReconnect() {
        viewModelScope.launch {
            // Suspends until a connected emission finally resolves the account, then stops collecting.
            // firstOrNull (not first) so a flow that ever completes returns null instead of throwing
            // NoSuchElementException out of this launch.
            val resolved = connectivityObserver.isConnected
                .filter { it }
                .firstOrNull { tryRestoreAccount() } != null
            // Account just resolved: tenant list Flows repopulate reactively via the active-account
            // gate, but the one-shot session fetches (roles / tags / favorites) already ran while empty,
            // so re-run them now that identity and connectivity are both available.
            if (resolved) sessionTaskRunner.runAll()
        }
    }

    fun onAuthComplete() {
        _isLoggedIn.value = true
        // The drawer conversation list is refreshed by the nav host alongside this call
        // (DrawerViewModel.refreshConversationsAfterLogin) — accountTransitions() doesn't fire on a
        // login-from-logged-out, so that crossing is wired explicitly there. Session tasks
        // (role fetch, tag refresh, favorites sync) already fired from AuthRepositoryImpl on the
        // preceding login/OAuth/2FA success, so we don't re-run them here.
        bannerStateHolder.fetchBanners()
        versionCheckStateHolder.checkBackendVersion()
    }

    fun setTabletSidebarOpen(open: Boolean) {
        viewModelScope.launch {
            settingsDataStore.setTabletSidebarOpen(open)
        }
    }

    // Toggles via the StateFlow, not a call-site boolean — Nav3 entry closures can capture stale state.
    fun toggleTabletSidebar() {
        setTabletSidebarOpen(tabletSidebarOpen.value != true)
    }

    fun dismissBanner(bannerId: String) {
        bannerStateHolder.dismissBanner(bannerId)
    }

    fun dismissVersionWarning() {
        versionCheckStateHolder.dismissVersionWarning()
    }

    fun dismissVersionWarningPermanently() {
        versionCheckStateHolder.dismissVersionWarningPermanently()
    }

    fun logout() {
        // Delegate to the account switcher's remove() (via the repository, which first revokes the
        // session server-side): it promotes the most-recently-used survivor — the user stays signed in
        // as that account — or, when this was the last account, tears down to logged-out and emits
        // session-expired to route to auth. Either way the accountTransitions collector above resets
        // this VM's in-memory state (config, sidebar mode, …) for both the Switched and Ended cases
        // (and DrawerViewModel resets the drawer data), so nothing is cleared imperatively here. Not
        // forcing the auth route here is what lets the successor promotion keep the user in the app.
        viewModelScope.launch { authRepository.logout() }
    }
}
