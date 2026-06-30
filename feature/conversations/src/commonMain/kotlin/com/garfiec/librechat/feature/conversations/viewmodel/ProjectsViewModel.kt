package com.garfiec.librechat.feature.conversations.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.data.repository.ProjectRepository
import com.garfiec.librechat.core.model.ChatProject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@Immutable
data class ProjectsUiState(
    val projects: List<ChatProject> = emptyList(),
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val nextCursor: String? = null,
    val hasMore: Boolean = true,
)

sealed interface ProjectsEvent {
    data class ShowError(val message: String) : ProjectsEvent
}

/** Browse-all-folders index (v0.8.7). Server is the source of truth — no local cache. */
class ProjectsViewModel(
    private val projectRepository: ProjectRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProjectsUiState())
    val uiState: StateFlow<ProjectsUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<ProjectsEvent>()
    val events: SharedFlow<ProjectsEvent> = _events.asSharedFlow()

    private val actions = ProjectActionsDelegate(
        scope = viewModelScope,
        projectRepository = projectRepository,
        onChanged = { refresh() },
        onDeleted = { refresh() },
        emitError = { _events.emit(ProjectsEvent.ShowError(it)) },
    )

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            fetch(cursor = null, replace = true)
            _uiState.value = _uiState.value.copy(isLoading = false)
        }
    }

    fun loadMore() {
        val cursor = _uiState.value.nextCursor ?: return
        // Guard against BOTH flags: a concurrent refresh (replace=true) plus this stale-cursor append
        // could duplicate ids, which crashes the LazyColumn's keyed items().
        if (_uiState.value.isLoading || _uiState.value.isRefreshing) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            fetch(cursor = cursor, replace = false)
            _uiState.value = _uiState.value.copy(isLoading = false)
        }
    }

    fun refresh() {
        if (_uiState.value.isRefreshing) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isRefreshing = true)
            fetch(cursor = null, replace = true)
            _uiState.value = _uiState.value.copy(isRefreshing = false)
        }
    }

    private suspend fun fetch(cursor: String?, replace: Boolean) {
        when (val result = projectRepository.listProjects(cursor)) {
            is Result.Success -> {
                // distinctBy on append: defense-in-depth against a duplicate id crashing the keyed list.
                val merged = if (replace) {
                    result.data.projects
                } else {
                    (_uiState.value.projects + result.data.projects).distinctBy { it.id }
                }
                _uiState.value = _uiState.value.copy(
                    projects = merged,
                    nextCursor = result.data.nextCursor,
                    hasMore = result.data.nextCursor != null,
                )
            }
            is Result.Error -> _uiState.value = _uiState.value.copy(error = result.message)
            is Result.Loading -> Unit
        }
    }

    fun createProject(name: String) = actions.create(name)

    fun renameProject(projectId: String, name: String) = actions.rename(projectId, name)

    fun deleteProject(projectId: String) = actions.delete(projectId)

    fun dismissError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}
