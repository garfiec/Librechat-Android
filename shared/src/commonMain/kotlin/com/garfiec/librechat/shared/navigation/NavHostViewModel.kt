package com.garfiec.librechat.shared.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import com.garfiec.librechat.core.common.network.ConnectivityObserver
import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.data.datastore.SettingsDataStore
import com.garfiec.librechat.core.data.repository.AuthRepository
import com.garfiec.librechat.core.data.repository.BannerRepository
import com.garfiec.librechat.core.data.repository.ConfigRepository
import com.garfiec.librechat.core.data.repository.ConversationRepository
import com.garfiec.librechat.core.data.repository.RoleRepository
import com.garfiec.librechat.core.data.repository.ShareRepository
import com.garfiec.librechat.core.data.repository.TagRepository
import com.garfiec.librechat.core.data.util.SessionTaskRunner
import com.garfiec.librechat.core.model.Banner
import com.garfiec.librechat.core.model.Conversation
import com.garfiec.librechat.core.model.ConversationTag
import com.garfiec.librechat.core.model.SAVED_TAG
import com.garfiec.librechat.core.model.permissions.Permission
import com.garfiec.librechat.core.model.permissions.PermissionType
import com.garfiec.librechat.core.model.permissions.hasAccessOrPermissive
import com.garfiec.librechat.core.network.client.ServerUrlProvider
import com.garfiec.librechat.core.network.client.TokenManager
import com.garfiec.librechat.feature.conversations.export.ConversationExporter
import com.garfiec.librechat.feature.conversations.export.ExportFormat
import com.garfiec.librechat.feature.conversations.viewmodel.ConversationListEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class NavHostViewModel(
    private val authRepository: AuthRepository,
    bannerRepository: BannerRepository,
    private val configRepository: ConfigRepository,
    private val conversationRepository: ConversationRepository,
    private val roleRepository: RoleRepository,
    private val sessionTaskRunner: SessionTaskRunner,
    private val tagRepository: TagRepository,
    private val tokenManager: TokenManager,
    private val settingsDataStore: SettingsDataStore,
    private val serverUrlProvider: ServerUrlProvider,
    private val shareRepository: ShareRepository,
    private val conversationExporter: ConversationExporter,
    private val connectivityObserver: ConnectivityObserver,
) : ViewModel() {

    private val bannerStateHolder = BannerStateHolder(bannerRepository, viewModelScope)
    private val versionCheckStateHolder = VersionCheckStateHolder(configRepository, settingsDataStore, viewModelScope)
    private val conversationListStateHolder = ConversationListStateHolder(conversationRepository, viewModelScope)

    private val favoriteConversations: StateFlow<List<Conversation>> =
        conversationListStateHolder.recentConversations
            .map { list -> list.filter { SAVED_TAG in it.tags } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Inputs for the drawer long-press action menu: the user-defined tags (excluding favorites,
    // same filter as ConversationListViewModel.observeTags) for the tag picker, plus the
    // config-driven shared-links flag that gates the Share action.
    private val drawerActionMenuState: StateFlow<DrawerActionMenuState> =
        combine(
            tagRepository.observeTags()
                .map { tags -> tags.filter { it.count > 0 && it.tag != SAVED_TAG } },
            configRepository.startupConfig,
        ) { tags, config ->
            DrawerActionMenuState(tags, config?.sharedLinksEnabled ?: false)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DrawerActionMenuState())

    // One-shot events for the drawer action menu (share-link copied, export-ready,
    // navigate-to-duplicate, errors). Reuses the conversation-list event type. extraBufferCapacity
    // lets emit() return immediately instead of suspending if the collector (ConversationActionEffects)
    // is momentarily absent — e.g. an action's result arriving as the drawer leaves composition.
    private val _events = MutableSharedFlow<ConversationListEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<ConversationListEvent> = _events.asSharedFlow()

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

    /**
     * Role-permission flags for drawer UI. Permissive-default (`?: true`) until the
     * role loads; once it does, denied permissions flip the flags off and the drawer
     * re-composes to hide the corresponding surfaces.
     */
    private val drawerPermissionFlags: StateFlow<DrawerPermissionFlags> =
        roleRepository.userPermissions
            .map { role ->
                DrawerPermissionFlags(
                    agentsEnabled = role.hasAccessOrPermissive(PermissionType.AGENTS, Permission.USE),
                    bookmarksEnabled = role.hasAccessOrPermissive(PermissionType.BOOKMARKS, Permission.USE),
                    // USE gate is fail-OPEN (read/list visibility); mutation gating
                    // lives fail-CLOSED inside feature/skills.
                    skillsEnabled = role.hasAccessOrPermissive(PermissionType.SKILLS, Permission.USE),
                )
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, DrawerPermissionFlags())

    val drawerUiState: StateFlow<DrawerUiState> = combine(
        combine(
            conversationListStateHolder.groupedConversations,
            conversationListStateHolder.activeConversationId,
            favoriteConversations,
            conversationListStateHolder.searchQuery,
        ) { grouped, activeId, favConvos, query ->
            DrawerDataSnapshot(grouped, activeId, favConvos, query)
        },
        combine(
            conversationListStateHolder.isRefreshing,
            conversationListStateHolder.isLoadingMore,
            conversationListStateHolder.hasMore,
        ) { refreshing, loadingMore, hasMore ->
            Triple(refreshing, loadingMore, hasMore)
        },
        drawerPermissionFlags,
        configRepository.endpointConfigs,
        drawerActionMenuState,
    ) { data, refreshState, perms, endpointConfigs, actionMenu ->
        val (refreshing, loadingMore, hasMore) = refreshState
        DrawerUiState(
            groupedConversations = data.grouped.map { (group, convos) ->
                group to convos.map { it.toDrawerDisplayData(data.activeId, endpointConfigs) }
            },
            favoriteConversations = data.favConvos.map {
                it.toDrawerDisplayData(data.activeId, endpointConfigs)
            },
            searchQuery = data.query,
            isRefreshing = refreshing,
            isLoadingMore = loadingMore,
            hasMore = hasMore,
            agentsEnabled = perms.agentsEnabled,
            bookmarksEnabled = perms.bookmarksEnabled,
            skillsEnabled = perms.skillsEnabled,
            availableTags = actionMenu.availableTags,
            sharedLinksEnabled = actionMenu.sharedLinksEnabled,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, DrawerUiState())

    private data class DrawerPermissionFlags(
        val agentsEnabled: Boolean = true,
        val bookmarksEnabled: Boolean = true,
        val skillsEnabled: Boolean = true,
    )

    private data class DrawerActionMenuState(
        val availableTags: List<ConversationTag> = emptyList(),
        val sharedLinksEnabled: Boolean = false,
    )

    init {
        viewModelScope.launch {
            try {
                // Wait for the server URL's async warm-up before any startup network work, so a
                // logged-in cold start can't fire requests (auth check, version/config fetch,
                // session tasks) at an empty base URL while ServerDataStore is still resolving.
                serverUrlProvider.awaitBaseUrl()
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
    }

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

    fun loadMoreConversations() {
        conversationListStateHolder.loadMoreConversations()
    }

    fun onAuthComplete() {
        _isLoggedIn.value = true
        conversationListStateHolder.refreshConversations()
        // Session tasks (role fetch, tag refresh, favorites sync) already fired from
        // AuthRepositoryImpl on the preceding login/OAuth/2FA success, so we don't
        // re-run them here — that was the source of the double-fetch on fresh login.
        bannerStateHolder.fetchBanners()
        versionCheckStateHolder.checkBackendVersion()
    }

    fun refreshConversations() {
        conversationListStateHolder.refreshConversations()
        viewModelScope.launch { tagRepository.refreshTags() }
    }

    fun setActiveConversation(conversationId: String?) {
        conversationListStateHolder.setActiveConversation(conversationId)
    }

    fun onSearchQueryChanged(query: String) {
        conversationListStateHolder.onSearchQueryChanged(query)
    }

    fun toggleFavorite(conversationId: String, currentTags: List<String>) {
        viewModelScope.launch {
            val result = tagRepository.toggleFavorite(conversationId, currentTags)
            if (result is Result.Error) {
                Logger.w(result.exception) { "Failed to toggle favorite for $conversationId" }
            }
        }
    }

    // --- Drawer conversation action menu (long-press). Bodies mirror ConversationListViewModel. ---

    fun renameConversation(id: String, newTitle: String) {
        viewModelScope.launch {
            try {
                conversationRepository.updateTitle(id, newTitle)
            } catch (e: Exception) {
                Logger.e(e) { "Failed to rename conversation" }
                _events.emit(ConversationListEvent.ShowError("Failed to rename conversation"))
            }
        }
    }

    fun archiveConversation(id: String) {
        viewModelScope.launch {
            try {
                conversationRepository.archive(id, true)
            } catch (e: Exception) {
                Logger.e(e) { "Failed to archive conversation" }
                _events.emit(ConversationListEvent.ShowError("Failed to archive conversation"))
            }
        }
    }

    fun deleteConversation(id: String) {
        viewModelScope.launch {
            try {
                conversationRepository.delete(id)
            } catch (e: Exception) {
                Logger.e(e) { "Failed to delete conversation" }
                _events.emit(ConversationListEvent.ShowError("Failed to delete conversation"))
            }
        }
    }

    fun shareConversation(conversationId: String) {
        viewModelScope.launch {
            when (val result = shareRepository.createShareLink(conversationId)) {
                is Result.Success -> {
                    _events.emit(ConversationListEvent.ShareLinkCopied(result.data))
                }
                is Result.Error -> {
                    _events.emit(
                        ConversationListEvent.ShowError(result.message ?: "Failed to create share link"),
                    )
                }
                is Result.Loading -> { /* no-op */ }
            }
        }
    }

    fun duplicateConversation(conversationId: String, title: String?) {
        viewModelScope.launch {
            when (val result = conversationRepository.duplicateConversation(conversationId, title)) {
                is Result.Success -> {
                    result.data.conversationId?.let { newId ->
                        _events.emit(ConversationListEvent.NavigateToConversation(newId))
                    }
                }
                is Result.Error -> {
                    _events.emit(
                        ConversationListEvent.ShowError(result.message ?: "Failed to duplicate conversation"),
                    )
                }
                is Result.Loading -> { /* no-op */ }
            }
        }
    }

    fun exportConversation(conversationId: String, title: String?, format: ExportFormat) {
        viewModelScope.launch {
            val result = when (format) {
                ExportFormat.JSON -> conversationExporter.exportAsJson(conversationId)
                ExportFormat.MARKDOWN -> conversationExporter.exportAsMarkdown(conversationId)
            }
            when (result) {
                is Result.Success -> {
                    _events.emit(
                        ConversationListEvent.ExportReady(
                            content = result.data,
                            format = format,
                            title = title ?: "conversation",
                        ),
                    )
                }
                is Result.Error -> {
                    _events.emit(
                        ConversationListEvent.ShowError(result.message ?: "Failed to export conversation"),
                    )
                }
                is Result.Loading -> { /* no-op */ }
            }
        }
    }

    /**
     * Persists the user-chosen tags for [conversationId], preserving favorite status.
     * [currentTags] is the conversation's existing tag list (to detect the SAVED_TAG).
     */
    fun updateConversationTags(conversationId: String, currentTags: List<String>, userTags: List<String>) {
        val wasFavorited = SAVED_TAG in currentTags
        val cleaned = userTags.filterNot { it == SAVED_TAG }
        val finalTags = if (wasFavorited) cleaned + SAVED_TAG else cleaned
        viewModelScope.launch {
            when (tagRepository.setConversationTags(conversationId, finalTags)) {
                is Result.Success -> {
                    tagRepository.refreshTags()
                }
                is Result.Error -> {
                    _events.emit(ConversationListEvent.ShowError("Failed to update tags"))
                }
                is Result.Loading -> { /* no-op */ }
            }
        }
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
        viewModelScope.launch {
            authRepository.logout()
            _isLoggedIn.value = false
            conversationListStateHolder.reset()
            tagRepository.clearCache()
            configRepository.clear()
            _sidebarMode.value = SidebarMode.Conversations
            _selectedSettingsCategory.value = null
        }
    }
}
