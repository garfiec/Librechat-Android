package com.garfiec.librechat.shared.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.data.datastore.SettingsDataStore
import com.garfiec.librechat.core.data.repository.AuthRepository
import com.garfiec.librechat.core.data.repository.BannerRepository
import com.garfiec.librechat.core.data.repository.ConfigRepository
import com.garfiec.librechat.core.data.repository.ConversationRepository
import com.garfiec.librechat.core.data.repository.RoleRepository
import com.garfiec.librechat.core.data.repository.TagRepository
import com.garfiec.librechat.core.data.util.SessionTaskRunner
import com.garfiec.librechat.core.model.Banner
import com.garfiec.librechat.core.model.Conversation
import com.garfiec.librechat.core.model.SAVED_TAG
import com.garfiec.librechat.core.model.permissions.Permission
import com.garfiec.librechat.core.model.permissions.PermissionType
import com.garfiec.librechat.core.model.permissions.hasAccessOrPermissive
import com.garfiec.librechat.core.network.client.ServerUrlProvider
import com.garfiec.librechat.core.network.client.TokenManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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
) : ViewModel() {

    private val bannerStateHolder = BannerStateHolder(bannerRepository, viewModelScope)
    private val versionCheckStateHolder = VersionCheckStateHolder(configRepository, settingsDataStore, viewModelScope)
    private val conversationListStateHolder = ConversationListStateHolder(conversationRepository, viewModelScope)

    private val favoriteConversations: StateFlow<List<Conversation>> =
        conversationListStateHolder.recentConversations
            .map { list -> list.filter { SAVED_TAG in it.tags } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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
    ) { data, refreshState, perms, endpointConfigs ->
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
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, DrawerUiState())

    private data class DrawerPermissionFlags(
        val agentsEnabled: Boolean = true,
        val bookmarksEnabled: Boolean = true,
        val skillsEnabled: Boolean = true,
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
                    versionCheckStateHolder.checkBackendVersion()
                    // Session tasks for the cold-start case (role fetch, tag refresh,
                    // favorites sync). Runs on the application scope so tasks outlive
                    // this VM's scope. Fresh logins fire these from AuthRepositoryImpl.
                    sessionTaskRunner.runAll()
                }
            } catch (e: Exception) {
                Logger.w(e) { "Failed to check auth state on init" }
            }
        }
        bannerStateHolder.fetchBanners()
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

    fun setTabletSidebarOpen(open: Boolean) {
        viewModelScope.launch {
            settingsDataStore.setTabletSidebarOpen(open)
        }
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
