package com.garfiec.librechat.shared.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import com.garfiec.librechat.core.common.BackendVersion
import com.garfiec.librechat.core.common.extensions.serverHostLabel
import com.garfiec.librechat.core.common.identity.AccountState
import com.garfiec.librechat.core.common.identity.AccountTransition
import com.garfiec.librechat.core.common.identity.ActiveAccountProvider
import com.garfiec.librechat.core.common.identity.accountTransitions
import com.garfiec.librechat.core.common.network.ConnectivityObserver
import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.data.datastore.AccountEntry
import com.garfiec.librechat.core.data.datastore.AccountRoster
import com.garfiec.librechat.core.data.datastore.SettingsDataStore
import com.garfiec.librechat.core.data.repository.AccountSwitcher
import com.garfiec.librechat.core.data.repository.AuthRepository
import com.garfiec.librechat.core.data.repository.BannerRepository
import com.garfiec.librechat.core.data.repository.ConfigRepository
import com.garfiec.librechat.core.data.repository.ConversationRepository
import com.garfiec.librechat.core.data.repository.EndpointTokenRepository
import com.garfiec.librechat.core.data.repository.ProjectRepository
import com.garfiec.librechat.core.data.repository.RoleRepository
import com.garfiec.librechat.core.data.repository.ShareRepository
import com.garfiec.librechat.core.data.repository.TagRepository
import com.garfiec.librechat.core.data.util.SessionTaskRunner
import com.garfiec.librechat.core.model.Banner
import com.garfiec.librechat.core.model.ChatProject
import com.garfiec.librechat.core.model.Conversation
import com.garfiec.librechat.core.model.ConversationTag
import com.garfiec.librechat.core.model.SAVED_TAG
import com.garfiec.librechat.core.model.permissions.Permission
import com.garfiec.librechat.core.model.permissions.PermissionType
import com.garfiec.librechat.core.model.permissions.canCreateSharedLinks
import com.garfiec.librechat.core.model.permissions.hasAccessOrPermissive
import com.garfiec.librechat.core.network.client.AccountReadyGate
import com.garfiec.librechat.core.network.client.ServerUrlProvider
import com.garfiec.librechat.core.network.client.TokenManager
import com.garfiec.librechat.feature.conversations.export.ConversationExporter
import com.garfiec.librechat.feature.conversations.export.ExportFormat
import com.garfiec.librechat.feature.conversations.viewmodel.ConversationListActionsDelegate
import com.garfiec.librechat.feature.conversations.viewmodel.ConversationListEvent
import com.garfiec.librechat.feature.conversations.viewmodel.ProjectActionsDelegate
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class NavHostViewModel(
    private val authRepository: AuthRepository,
    bannerRepository: BannerRepository,
    private val configRepository: ConfigRepository,
    private val conversationRepository: ConversationRepository,
    private val roleRepository: RoleRepository,
    private val sessionTaskRunner: SessionTaskRunner,
    private val tagRepository: TagRepository,
    private val projectRepository: ProjectRepository,
    private val tokenManager: TokenManager,
    private val accountReadyGate: AccountReadyGate,
    private val settingsDataStore: SettingsDataStore,
    private val serverUrlProvider: ServerUrlProvider,
    private val shareRepository: ShareRepository,
    private val conversationExporter: ConversationExporter,
    private val connectivityObserver: ConnectivityObserver,
    private val endpointTokenRepository: EndpointTokenRepository,
    private val activeAccountProvider: ActiveAccountProvider,
    private val accountRoster: AccountRoster,
    private val accountSwitcher: AccountSwitcher,
) : ViewModel() {

    private val bannerStateHolder = BannerStateHolder(bannerRepository, viewModelScope)
    private val versionCheckStateHolder =
        VersionCheckStateHolder(configRepository, settingsDataStore, serverUrlProvider, viewModelScope)
    private val conversationListStateHolder = ConversationListStateHolder(conversationRepository, viewModelScope)

    private val favoriteConversations: StateFlow<List<Conversation>> =
        conversationListStateHolder.recentConversations
            .map { list -> list.filter { SAVED_TAG in it.tags } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val pinnedConversations: StateFlow<List<Conversation>> =
        conversationListStateHolder.recentConversations
            .map { list -> list.filter { it.pinned == true } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Inputs for the drawer long-press action menu: the user-defined tags (excluding favorites,
    // same filter as ConversationListViewModel.observeTags) for the tag picker, plus the
    // config-driven shared-links flag and the SHARED_LINKS role permission that gate the Share action.
    private val drawerActionMenuState: StateFlow<DrawerActionMenuState> =
        combine(
            tagRepository.observeTags()
                .map { tags -> tags.filter { it.count > 0 && it.tag != SAVED_TAG } },
            configRepository.startupConfig,
            configRepository.detectedBackendVersion,
            roleRepository.userPermissions,
        ) { tags, config, version, permissions ->
            // Pin requires POST /api/convos/pin (v0.8.7+). Gate fail-closed on unknown
            // version so older servers don't surface an action they'd 404 on.
            val supportsV087 = version != null && BackendVersion.isCompatibleOrNewer(version, "0.8.7")
            val canShare = permissions.canCreateSharedLinks(config?.sharedLinksEnabled ?: false)
            DrawerActionMenuState(tags, canShare, supportsV087, supportsV087)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DrawerActionMenuState())

    // Chat Projects (v0.8.7). Loaded lazily for the move-to-project picker and the drawer's Projects
    // tab folder list; the server is the source of truth (no local cache).
    private val _projects = MutableStateFlow<List<ChatProject>>(emptyList())
    val projects: StateFlow<List<ChatProject>> = _projects.asStateFlow()

    // Inline project-chat accordion for the drawer Projects tab. Network-direct per project (Room
    // can't filter by project — see ProjectChatsViewModel), single-expand: only the open project's
    // chats are held. Mapped to the drawer row type against the active id + endpoint configs so the
    // rows match the recents list.
    private val _expandedProjectId = MutableStateFlow<String?>(null)
    private val _expandedProjectChats = MutableStateFlow<List<Conversation>>(emptyList())
    private val _expandedProjectLoading = MutableStateFlow(false)

    val inlineProjectChats: StateFlow<InlineProjectChatsState> = combine(
        _expandedProjectId,
        _expandedProjectChats,
        _expandedProjectLoading,
        conversationListStateHolder.activeConversationId,
        configRepository.endpointConfigs,
    ) { expandedId, convos, loading, activeId, configs ->
        InlineProjectChatsState(
            expandedProjectId = expandedId,
            conversations = convos.map { it.toDrawerDisplayData(activeId, configs) },
            isLoading = loading,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), InlineProjectChatsState())

    // One-shot events for the drawer action menu (share-link copied, export-ready,
    // navigate-to-duplicate, errors). Reuses the conversation-list event type. extraBufferCapacity
    // lets emit() return immediately instead of suspending if the collector (ConversationActionEffects)
    // is momentarily absent — e.g. an action's result arriving as the drawer leaves composition.
    private val _events = MutableSharedFlow<ConversationListEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<ConversationListEvent> = _events.asSharedFlow()

    // Shared row-action logic (rename/archive/delete/share/duplicate/export) — the same delegate
    // ConversationListViewModel and ProjectChatsViewModel use, so the drawer long-press menu surfaces
    // Result.Error failures (toast) instead of swallowing them in a dead try/catch around a
    // Result-returning repo call. Room-backed here, so onMutated is a no-op: the conversation
    // observe-Flow already re-emits after a mutation.
    private val conversationActions = ConversationListActionsDelegate(
        scope = viewModelScope,
        events = _events,
        conversationRepository = conversationRepository,
        tagRepository = tagRepository,
        shareRepository = shareRepository,
        conversationExporter = conversationExporter,
        onMutated = {},
    )

    // Shared project CRUD (same delegate ProjectsViewModel uses).
    private val projectActions = ProjectActionsDelegate(
        scope = viewModelScope,
        projectRepository = projectRepository,
        onChanged = { loadProjects() },
        onDeleted = { loadProjects() },
        emitError = { _events.emit(ConversationListEvent.ShowError(it)) },
    )

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
    // recency (matching "switch to most-recently-active" on remove).
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

    // Drawer "Library" tab is optimistic local state (instant toggle) with DataStore as a write-behind
    // cache: hydrated once in init and written on change. Null = not yet hydrated; the drawer shows
    // Chats for the brief window until the persisted value loads, and the seed's update { it ?: ... }
    // lets a tap in that window win over the incoming persisted value.
    private val _drawerLibraryTab = MutableStateFlow<DrawerTab?>(null)
    val drawerLibraryTab: StateFlow<DrawerTab?> = _drawerLibraryTab.asStateFlow()

    fun setDrawerLibraryTab(tab: DrawerTab) {
        _drawerLibraryTab.value = tab
        viewModelScope.launch { settingsDataStore.setDrawerLibraryTab(tab.toStorageString()) }
    }

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
            pinnedConversations,
            conversationListStateHolder.searchQuery,
        ) { grouped, activeId, favConvos, pinnedConvos, query ->
            DrawerDataSnapshot(grouped, activeId, favConvos, pinnedConvos, query)
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
            pinnedConversations = data.pinnedConvos.map {
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
            pinEnabled = actionMenu.pinEnabled,
            projectsEnabled = actionMenu.projectsEnabled,
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
        val pinEnabled: Boolean = false,
        val projectsEnabled: Boolean = false,
    )

    init {
        viewModelScope.launch {
            val persisted = DrawerTab.fromString(settingsDataStore.drawerLibraryTab.first())
            _drawerLibraryTab.update { it ?: persisted }
        }
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
        // Load the Chat Projects folders once the backend is known to support them (v0.8.7+).
        // detectedBackendVersion is a StateFlow (already conflated), so no distinctUntilChanged.
        viewModelScope.launch {
            configRepository.detectedBackendVersion.collect { version ->
                if (version != null && BackendVersion.isCompatibleOrNewer(version, "0.8.7")) {
                    loadProjects()
                }
            }
        }
        // The active account changed underneath this Activity-scoped VM (switch / add-completion /
        // remove → Switched; remove-last → Ended; plain logout also lands here as Ended, where the
        // clears below just repeat logout()'s — idempotent). Room-backed state re-filters
        // reactively, but everything held in-memory here or in the singleton repos is account- or
        // server-blind and must not survive the flip.
        viewModelScope.launch {
            activeAccountProvider.accountTransitions().collect { transition ->
                _projects.value = emptyList()
                _expandedProjectId.value = null
                _expandedProjectChats.value = emptyList()
                _expandedProjectLoading.value = false
                _sidebarMode.value = SidebarMode.Conversations
                _selectedSettingsCategory.value = null
                conversationListStateHolder.reset()
                tagRepository.clearCache()
                endpointTokenRepository.clear()
                // Reseed the in-memory config from the (already-flipped) server's own srv:-keyed
                // cache — warm on switch-back — instead of clear(), which would wipe every server's
                // disk cache.
                configRepository.reloadForActiveServer()
                if (transition is AccountTransition.Switched) {
                    // The switch path never runs the login-side session machinery
                    // (AuthRepositoryImpl fires these on sign-in; logout/re-auth via onAuthComplete)
                    // so the incoming account's session state is fetched here.
                    conversationListStateHolder.refreshConversations()
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

    // --- Drawer conversation action menu (long-press). Row actions route through the shared
    // ConversationListActionsDelegate; favorite/pin/tags stay local (different signatures). ---

    fun renameConversation(id: String, newTitle: String) = conversationActions.renameConversation(id, newTitle)

    fun archiveConversation(id: String) = conversationActions.archiveConversation(id)

    fun pinConversation(id: String, pinned: Boolean) {
        viewModelScope.launch {
            val result = conversationRepository.pin(id, pinned)
            if (result is Result.Error) {
                Logger.e(result.exception) { "Failed to pin conversation" }
                _events.emit(ConversationListEvent.ShowError("Failed to pin conversation"))
            }
        }
    }

    /**
     * Toggles the inline accordion for [projectId] in the drawer's Projects tab. Expanding loads that
     * project's chats network-direct (single-expand: the previously open project collapses); tapping
     * the open project collapses it. [ChatProject.UNASSIGNED] is a valid id (loose chats).
     */
    fun toggleProjectExpanded(projectId: String) {
        if (_expandedProjectId.value == projectId) {
            _expandedProjectId.value = null
            _expandedProjectChats.value = emptyList()
            return
        }
        _expandedProjectId.value = projectId
        _expandedProjectChats.value = emptyList()
        _expandedProjectLoading.value = true
        viewModelScope.launch {
            val result = conversationRepository.getConversationsForProject(projectId = projectId)
            // The user may have collapsed or opened another folder while this was in flight.
            if (_expandedProjectId.value != projectId) return@launch
            when (result) {
                is Result.Success -> _expandedProjectChats.value = result.data.conversations
                is Result.Error -> Logger.w(result.exception) { "Failed to load project chats" }
                is Result.Loading -> Unit
            }
            _expandedProjectLoading.value = false
        }
    }

    /** Loads the user's projects for the move-to-project picker. */
    fun loadProjects() {
        viewModelScope.launch {
            when (val result = projectRepository.listProjects()) {
                is Result.Success -> _projects.value = result.data.projects
                is Result.Error -> Logger.w(result.exception) { "Failed to load projects" }
                is Result.Loading -> Unit
            }
        }
    }

    /** Assigns [conversationId] to [projectId], or unassigns it when null. */
    fun moveConversationToProject(conversationId: String, projectId: String?) {
        viewModelScope.launch {
            when (val result = projectRepository.assignConversation(conversationId, projectId)) {
                is Result.Success -> onProjectAssignmentChanged()
                is Result.Error -> {
                    Logger.e(result.exception) { "Failed to move conversation to project" }
                    _events.emit(ConversationListEvent.ShowError("Failed to move conversation"))
                }
                is Result.Loading -> Unit
            }
        }
    }

    /** Creates a project named [name] and assigns [conversationId] to it. */
    fun createProjectAndAssign(conversationId: String, name: String) {
        viewModelScope.launch {
            when (val created = projectRepository.createProject(name)) {
                is Result.Success -> {
                    when (val assign = projectRepository.assignConversation(conversationId, created.data.id)) {
                        is Result.Error -> {
                            Logger.e(assign.exception) { "Failed to assign new project" }
                            _events.emit(ConversationListEvent.ShowError("Failed to move conversation"))
                        }
                        is Result.Success -> onProjectAssignmentChanged()
                        is Result.Loading -> Unit
                    }
                }
                is Result.Error -> {
                    Logger.e(created.exception) { "Failed to create project" }
                    _events.emit(ConversationListEvent.ShowError("Failed to create project"))
                }
                is Result.Loading -> Unit
            }
        }
    }

    /**
     * Refresh the Projects mode after a conversation's project assignment changes: reload the
     * folders, whose conversationCount is now stale.
     */
    private fun onProjectAssignmentChanged() {
        loadProjects()
    }

    fun createProject(name: String) = projectActions.create(name)

    fun renameProject(projectId: String, name: String) = projectActions.rename(projectId, name)

    fun deleteProject(projectId: String) {
        // Collapse the inline accordion if the deleted project was open, so its stale chats clear.
        if (_expandedProjectId.value == projectId) {
            _expandedProjectId.value = null
            _expandedProjectChats.value = emptyList()
        }
        projectActions.delete(projectId)
    }

    fun deleteConversation(id: String) = conversationActions.deleteConversation(id)

    fun shareConversation(conversationId: String) = conversationActions.shareConversation(conversationId)

    fun duplicateConversation(conversationId: String, title: String?) =
        conversationActions.duplicateConversation(conversationId, title)

    fun exportConversation(conversationId: String, title: String?, format: ExportFormat) =
        conversationActions.exportConversation(conversationId, title, format)

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
        // Delegate to the account switcher's remove() (via the repository, which first revokes the
        // session server-side): it promotes the most-recently-used survivor — the user stays signed in
        // as that account — or, when this was the last account, tears down to logged-out and emits
        // session-expired to route to auth. Either way the accountTransitions collector above resets
        // this VM's in-memory state (conversation list, tags, projects, config, …) for both the
        // Switched and Ended cases, so nothing is cleared imperatively here. Not forcing the auth
        // route here is what lets the successor promotion keep the user in the app.
        viewModelScope.launch { authRepository.logout() }
    }
}
