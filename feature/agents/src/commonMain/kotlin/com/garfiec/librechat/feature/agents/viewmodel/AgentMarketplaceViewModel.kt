package com.garfiec.librechat.feature.agents.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.data.datastore.ServerDataStore
import com.garfiec.librechat.core.data.repository.AgentRepository
import com.garfiec.librechat.core.data.repository.RoleRepository
import com.garfiec.librechat.core.data.util.PermissionGate
import com.garfiec.librechat.core.model.Agent
import com.garfiec.librechat.core.model.permissions.Permission
import com.garfiec.librechat.core.model.permissions.PermissionType
import com.garfiec.librechat.core.model.permissions.hasAccessOrPermissive
import com.garfiec.librechat.feature.agents.AgentCardDisplayData
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@Immutable
data class AgentMarketplaceUiState(
    val agents: List<AgentCardDisplayData> = emptyList(),
    val filteredAgents: List<AgentCardDisplayData> = emptyList(),
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val selectedCategory: String? = null,
    val searchQuery: String = "",
    val categories: List<String> = emptyList(),
    val hasMore: Boolean = true,
    val currentPage: Int = 1,
    // Role-permission gates — default permissive.
    val agentsEnabled: Boolean = true,
    val agentsCreateEnabled: Boolean = true,
    val marketplaceEnabled: Boolean = true,
)

class AgentMarketplaceViewModel(
    private val agentRepository: AgentRepository,
    private val serverDataStore: ServerDataStore,
    private val roleRepository: RoleRepository,
    private val permissionGate: PermissionGate,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AgentMarketplaceUiState())
    val uiState: StateFlow<AgentMarketplaceUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null

    companion object {
        private const val PAGE_SIZE = 10
        private const val SEARCH_DEBOUNCE_MS = 500L
    }

    private fun Agent.toCardDisplayData(): AgentCardDisplayData {
        val resolvedUrl = avatarUrl?.let { url ->
            if (url.startsWith("http")) {
                url
            } else {
                "${serverDataStore.getBaseUrl()}$url"
            }
        }
        return AgentCardDisplayData(
            id = id,
            name = name ?: "Unnamed Agent",
            description = description,
            avatarUrl = resolvedUrl,
            author = author,
            authorName = authorName,
        )
    }

    init {
        // Categories endpoint is ungated server-side — load unconditionally.
        loadCategories()
        observePermissionFlags()
        loadInitialAgents()
    }

    /** Continuous collector mirroring the current role into UiState permission flags. */
    private fun observePermissionFlags() {
        viewModelScope.launch {
            roleRepository.userPermissions.collect { role ->
                _uiState.value = _uiState.value.copy(
                    agentsEnabled = role.hasAccessOrPermissive(PermissionType.AGENTS, Permission.USE),
                    agentsCreateEnabled = role.hasAccessOrPermissive(PermissionType.AGENTS, Permission.CREATE),
                    marketplaceEnabled = role.hasAccessOrPermissive(PermissionType.MARKETPLACE, Permission.USE),
                )
            }
        }
    }

    /** Fetches the first page of agents once the role confirms AGENTS.USE. Permissive on timeout. */
    private fun loadInitialAgents() {
        viewModelScope.launch {
            if (permissionGate.awaitRole()?.hasAccess(PermissionType.AGENTS, Permission.USE) != false) {
                loadAgents()
            }
        }
    }

    fun loadAgents() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoading = true,
                error = null,
                currentPage = 1,
                hasMore = true,
            )
            val state = _uiState.value
            when (val result = agentRepository.getAgentsPaginated(
                page = 1,
                limit = PAGE_SIZE,
                search = state.searchQuery.ifBlank { null },
                category = state.selectedCategory,
            )) {
                is Result.Success -> {
                    val displayAgents = result.data.agents.map { it.toCardDisplayData() }
                    _uiState.value = _uiState.value.copy(
                        agents = displayAgents,
                        filteredAgents = displayAgents,
                        isLoading = false,
                        hasMore = result.data.hasMore,
                        currentPage = 1,
                    )
                }
                is Result.Error -> {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = result.message ?: "Failed to load agents",
                    )
                }
                is Result.Loading -> { /* no-op */ }
            }
        }
    }

    /** Fetches the next page and appends results. No-ops if already loading or no more pages. */
    fun loadMore() {
        val state = _uiState.value
        if (!state.hasMore || state.isLoadingMore || state.isLoading) return

        viewModelScope.launch {
            val nextPage = state.currentPage + 1
            _uiState.value = state.copy(isLoadingMore = true)
            when (val result = agentRepository.getAgentsPaginated(
                page = nextPage,
                limit = PAGE_SIZE,
                search = state.searchQuery.ifBlank { null },
                category = state.selectedCategory,
            )) {
                is Result.Success -> {
                    val currentAgents = _uiState.value.agents
                    val newAgents = currentAgents + result.data.agents.map { it.toCardDisplayData() }
                    _uiState.value = _uiState.value.copy(
                        agents = newAgents,
                        filteredAgents = newAgents,
                        isLoadingMore = false,
                        hasMore = result.data.hasMore,
                        currentPage = nextPage,
                    )
                }
                is Result.Error -> {
                    _uiState.value = _uiState.value.copy(
                        isLoadingMore = false,
                        error = result.message ?: "Failed to load more agents",
                    )
                }
                is Result.Loading -> { /* no-op */ }
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isRefreshing = true)
            val state = _uiState.value
            when (val result = agentRepository.getAgentsPaginated(
                page = 1,
                limit = PAGE_SIZE,
                search = state.searchQuery.ifBlank { null },
                category = state.selectedCategory,
            )) {
                is Result.Success -> {
                    val displayAgents = result.data.agents.map { it.toCardDisplayData() }
                    _uiState.value = _uiState.value.copy(
                        agents = displayAgents,
                        filteredAgents = displayAgents,
                        isRefreshing = false,
                        hasMore = result.data.hasMore,
                        currentPage = 1,
                    )
                }
                is Result.Error -> {
                    _uiState.value = _uiState.value.copy(
                        isRefreshing = false,
                        error = result.message ?: "Failed to refresh agents",
                    )
                }
                is Result.Loading -> { /* no-op */ }
            }
            loadCategories()
        }
    }

    /** Reloads on screen resume so a delete (or edit) made on a pushed detail screen
     *  is reflected when the user returns — the marketplace VM is retained in the back
     *  stack and otherwise only fetches on init/search/category/pull. Skips while a load
     *  is already in flight so an ON_RESUME mid-scroll/refresh doesn't stomp it; the
     *  first resume (empty list) is covered by the init load. Mirrors
     *  SkillsListViewModel.refreshOnReturn(). */
    fun refreshOnReturn() {
        val state = _uiState.value
        if (state.isLoading || state.isLoadingMore || state.isRefreshing) return
        if (state.agents.isEmpty()) loadAgents() else refresh()
    }

    fun onSearchQueryChanged(query: String) {
        _uiState.value = _uiState.value.copy(searchQuery = query)
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            loadAgents()
        }
    }

    fun onCategorySelected(category: String?) {
        _uiState.value = _uiState.value.copy(
            selectedCategory = if (_uiState.value.selectedCategory == category) null else category,
        )
        loadAgents()
    }

    fun dismissError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    private fun loadCategories() {
        viewModelScope.launch {
            when (val result = agentRepository.getAgentCategories()) {
                is Result.Success -> {
                    _uiState.value = _uiState.value.copy(
                        categories = result.data.map { it.value },
                    )
                }
                is Result.Error -> { /* categories are non-critical */ }
                is Result.Loading -> { /* no-op */ }
            }
        }
    }
}
