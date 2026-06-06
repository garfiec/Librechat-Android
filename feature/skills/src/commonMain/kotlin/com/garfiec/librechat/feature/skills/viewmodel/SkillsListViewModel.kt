package com.garfiec.librechat.feature.skills.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.data.repository.RoleRepository
import com.garfiec.librechat.core.data.repository.SkillsRepository
import com.garfiec.librechat.core.model.SkillSummary
import com.garfiec.librechat.core.model.permissions.Permission
import com.garfiec.librechat.core.model.permissions.PermissionType
import com.garfiec.librechat.core.model.permissions.hasAccessStrictOrDenied
import com.garfiec.librechat.feature.skills.components.PickedDocument
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@Immutable
data class SkillsListUiState(
    val skills: List<SkillSummary> = emptyList(),
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val searchQuery: String = "",
    val hasMore: Boolean = false,
    val cursor: String? = null,
    /** Fail-CLOSED: only true when SKILLS.CREATE is explicitly granted. Hides the
     *  create + import affordances unless the server would allow it. */
    val canCreate: Boolean = false,
    val isImporting: Boolean = false,
)

class SkillsListViewModel(
    private val skillsRepository: SkillsRepository,
    private val roleRepository: RoleRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SkillsListUiState())
    val uiState: StateFlow<SkillsListUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null

    init {
        observeCreatePermission()
        // Initial + on-return load is driven by the screen's ON_RESUME effect so a
        // skill created in the editor shows on return (Nav3 retains this VM, so
        // init runs only once and wouldn't re-fetch). See SkillsListScreen.
    }

    /** Reloads on screen resume. Skips while a load is already in flight so an
     *  ON_RESUME mid-scroll/refresh doesn't stomp an in-flight page; the first
     *  resume (empty list) does the initial load. */
    fun refreshOnReturn() {
        val state = _uiState.value
        if (state.isLoading || state.isLoadingMore || state.isRefreshing) return
        if (state.skills.isEmpty()) loadFirstPage() else refresh()
    }

    /** Fail-CLOSED create gate (mutations hide unless explicitly permitted). */
    private fun observeCreatePermission() {
        viewModelScope.launch {
            roleRepository.userPermissions.collect { role ->
                _uiState.value = _uiState.value.copy(
                    canCreate = role.hasAccessStrictOrDenied(PermissionType.SKILLS, Permission.CREATE),
                )
            }
        }
    }

    fun loadFirstPage() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            fetchPage(cursor = null, replace = true)
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isRefreshing = true, error = null)
            fetchPage(cursor = null, replace = true)
        }
    }

    fun loadMore() {
        val state = _uiState.value
        if (!state.hasMore || state.isLoadingMore || state.isLoading) return
        viewModelScope.launch {
            _uiState.value = state.copy(isLoadingMore = true)
            fetchPage(cursor = state.cursor, replace = false)
        }
    }

    /** Imports a skill from a picked .md/.zip/.skill and refreshes on success.
     *  Server validation `issues` surface in [SkillsListUiState.error]. */
    fun importSkill(doc: PickedDocument) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isImporting = true, error = null)
            when (val result = skillsRepository.importSkill(doc.bytes, doc.filename, doc.mimeType)) {
                is Result.Success -> {
                    _uiState.value = _uiState.value.copy(isImporting = false)
                    loadFirstPage()
                }
                is Result.Error ->
                    _uiState.value = _uiState.value.copy(
                        isImporting = false,
                        error = result.message ?: "Failed to import skill",
                    )
                is Result.Loading -> { /* no-op */ }
            }
        }
    }

    fun onSearchQueryChanged(query: String) {
        _uiState.value = _uiState.value.copy(searchQuery = query)
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            fetchPage(cursor = null, replace = true)
        }
    }

    private suspend fun fetchPage(cursor: String?, replace: Boolean) {
        val query = _uiState.value.searchQuery.takeIf { it.isNotBlank() }
        when (val result = skillsRepository.listSkills(search = query, cursor = cursor)) {
            is Result.Success -> {
                val page = result.data
                val merged = if (replace) page.skills else _uiState.value.skills + page.skills
                _uiState.value = _uiState.value.copy(
                    skills = merged,
                    hasMore = page.hasMore,
                    cursor = page.after,
                    isLoading = false,
                    isLoadingMore = false,
                    isRefreshing = false,
                    error = null,
                )
            }
            is Result.Error -> {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    isLoadingMore = false,
                    isRefreshing = false,
                    error = result.message ?: "Failed to load skills",
                )
            }
            is Result.Loading -> { /* no-op */ }
        }
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 400L
    }
}
