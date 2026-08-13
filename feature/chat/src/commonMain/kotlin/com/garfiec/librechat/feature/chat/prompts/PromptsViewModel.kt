package com.garfiec.librechat.feature.chat.prompts

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.data.repository.PromptRepository
import com.garfiec.librechat.core.data.repository.RoleRepository
import com.garfiec.librechat.core.data.util.PermissionGate
import com.garfiec.librechat.core.model.PromptGroup
import com.garfiec.librechat.core.model.permissions.Permission
import com.garfiec.librechat.core.model.permissions.PermissionType
import com.garfiec.librechat.core.model.permissions.hasAccessOrPermissive
import com.garfiec.librechat.core.model.request.UpdatePromptTagRequest
import com.garfiec.librechat.feature.chat.prompts.components.PromptSortOrder
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@Immutable
data class PromptsUiState(
    val groups: List<PromptGroupDisplayData> = emptyList(),
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val selectedGroup: PromptGroupDetailDisplayData? = null,
    val totalPages: Int = 0,
    val currentPage: Int = 1,
    // Filter and sort state
    val selectedCategory: String? = null,
    val sortOrder: PromptSortOrder = PromptSortOrder.RECENT,
    val availableCategories: List<String> = emptyList(),
    val showFilterSheet: Boolean = false,
    val filteredGroups: List<PromptGroupDisplayData> = emptyList(),
    // Share state
    val showShareDialog: Boolean = false,
    val shareGroupId: String? = null,
    // Variable dialog state
    val showVariableDialog: Boolean = false,
    val variablePromptTemplate: String = "",
    val variableNames: List<String> = emptyList(),
    // Role-permission gates — default permissive.
    val promptsCreateEnabled: Boolean = true,
    val promptsShareEnabled: Boolean = true,
)

class PromptsViewModel(
    private val promptRepository: PromptRepository,
    private val roleRepository: RoleRepository,
    private val permissionGate: PermissionGate,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PromptsUiState())
    val uiState: StateFlow<PromptsUiState> = _uiState.asStateFlow()

    // Keep raw domain groups for filtering/sorting by fields not in display data
    private var rawGroups: List<PromptGroup> = emptyList()

    // The PromptRepository.revision this list was built from, and the one the in-flight fetch is
    // for. Null until a load succeeds, so a failed one is retried on the next visit.
    private var loadedRevision: Long? = null
    private var requestedRevision: Long? = null
    private var reloadJob: Job? = null

    init {
        observePermissionFlags()
        loadInitialGroups()
    }

    /**
     * Bumped when any prompt is created, edited or deleted — the signal this list is stale.
     * Read from the screen's composition and paired with [refreshIfStale].
     */
    val promptLibraryRevision: StateFlow<Long> = promptRepository.revision

    /**
     * Re-reads the list, but only when a prompt actually changed since it was loaded.
     *
     * Failures stay silent: nobody asked for this fetch, and reporting it would put "Failed to
     * refresh prompts" on screen after a save that worked.
     */
    fun refreshIfStale() {
        val current = promptRepository.revision.value
        if (current == loadedRevision) return
        // Already fetching this revision (the initial load): joining it is what keeps the first
        // composition from duplicating it.
        if (reloadJob?.isActive == true && requestedRevision == current) return
        launchReload(current, surfaceErrors = false, fullSpinner = false)
    }

    /** Continuous collector mirroring CREATE/SHARE sub-action flags into UiState. */
    private fun observePermissionFlags() {
        viewModelScope.launch {
            roleRepository.userPermissions.collect { role ->
                _uiState.value = _uiState.value.copy(
                    promptsCreateEnabled = role.hasAccessOrPermissive(PermissionType.PROMPTS, Permission.CREATE),
                    promptsShareEnabled = role.hasAccessOrPermissive(PermissionType.PROMPTS, Permission.SHARE),
                )
            }
        }
    }

    /** Fetches the first page of prompt groups once the role confirms PROMPTS.USE. Permissive on timeout. */
    private fun loadInitialGroups() {
        viewModelScope.launch {
            if (permissionGate.awaitRole()?.hasAccess(PermissionType.PROMPTS, Permission.USE) != false) {
                loadGroups()
            }
        }
    }

    /** The initial load and the empty-state retry: a full-screen spinner, failures shown. */
    fun loadGroups() {
        launchReload(promptRepository.revision.value, surfaceErrors = true, fullSpinner = true)
    }

    /** The pull-to-refresh gesture. Its failures are the user's to see. */
    fun refresh() {
        launchReload(promptRepository.revision.value, surfaceErrors = true, fullSpinner = false)
    }

    private fun launchReload(revision: Long, surfaceErrors: Boolean, fullSpinner: Boolean) {
        // Cancel-replace: the fetches are unordered and the write is last-writer-wins, so a
        // superseded reload could otherwise land on top of a newer list.
        reloadJob?.cancel()
        requestedRevision = revision
        reloadJob = viewModelScope.launch { fetchGroups(revision, surfaceErrors, fullSpinner) }
    }

    private suspend fun fetchGroups(revision: Long, surfaceErrors: Boolean, fullSpinner: Boolean) {
        _uiState.value = _uiState.value.copy(
            isLoading = fullSpinner,
            isRefreshing = !fullSpinner,
            error = if (surfaceErrors) null else _uiState.value.error,
        )
        when (val result = promptRepository.getGroups()) {
            is Result.Success -> {
                val response = result.data
                val groups = response.promptGroups
                rawGroups = groups
                loadedRevision = revision
                val categories = groups
                    .mapNotNull { it.category }
                    .filter { it.isNotBlank() }
                    .distinct()
                    .sorted()

                _uiState.value = _uiState.value.copy(
                    groups = groups.map { it.toDisplayData() },
                    totalPages = if (response.hasMore) 9999 else 1,
                    currentPage = 1,
                    isLoading = false,
                    isRefreshing = false,
                    availableCategories = categories,
                )
                applyFilters()
            }
            is Result.Error -> {
                Logger.d(result.exception) { "Failed to load prompts: ${result.message}" }
                val message = result.message
                    ?: if (fullSpinner) "Failed to load prompts" else "Failed to refresh prompts"
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    isRefreshing = false,
                    error = if (surfaceErrors) message else _uiState.value.error,
                )
            }
            is Result.Loading -> { /* no-op */ }
        }
    }

    fun selectGroup(groupId: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            val groupResult = promptRepository.getGroup(groupId)
            val promptsResult = promptRepository.getPromptsByGroupId(groupId)

            when (groupResult) {
                is Result.Success -> {
                    val group = groupResult.data
                    val prompts = (promptsResult as? Result.Success)?.data ?: emptyList()
                    val mergedGroup = group.copy(prompts = prompts)
                    _uiState.value = _uiState.value.copy(
                        selectedGroup = mergedGroup.toDetailDisplayData(),
                        isLoading = false,
                    )
                }
                is Result.Error -> {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = groupResult.message ?: "Failed to load prompt details",
                    )
                }
                is Result.Loading -> { /* no-op */ }
            }
        }
    }

    fun clearSelectedGroup() {
        _uiState.value = _uiState.value.copy(selectedGroup = null)
    }

    fun onCategorySelected(category: String?) {
        _uiState.value = _uiState.value.copy(selectedCategory = category)
        applyFilters()
    }

    fun onSortOrderChanged(sortOrder: PromptSortOrder) {
        _uiState.value = _uiState.value.copy(sortOrder = sortOrder)
        applyFilters()
    }

    fun showFilterSheet() {
        _uiState.value = _uiState.value.copy(showFilterSheet = true)
    }

    fun dismissFilterSheet() {
        _uiState.value = _uiState.value.copy(showFilterSheet = false)
    }

    private fun applyFilters() {
        val state = _uiState.value
        var filtered = rawGroups

        // Apply category filter
        val selectedCat = state.selectedCategory
        if (selectedCat != null) {
            filtered = filtered.filter { it.category == selectedCat }
        }

        // Apply sort
        filtered = when (state.sortOrder) {
            PromptSortOrder.RECENT -> filtered.sortedByDescending { it.updatedAt ?: it.createdAt }
            PromptSortOrder.POPULAR -> filtered.sortedByDescending { it.numberOfGenerations }
            PromptSortOrder.ALPHABETICAL -> filtered.sortedBy { it.name.lowercase() }
        }

        _uiState.value = state.copy(filteredGroups = filtered.map { it.toDisplayData() })
    }

    fun deleteGroup(groupId: String) {
        viewModelScope.launch {
            // Close the detail view only on Success — doing it unconditionally reports a prompt as
            // gone while it is still on the server. `delete` is safeApiCall-wrapped, so failure
            // arrives as a returned Result.Error and a try/catch here would never see it.
            when (val result = promptRepository.delete(groupId)) {
                is Result.Success -> _uiState.value = _uiState.value.copy(selectedGroup = null)
                is Result.Error -> {
                    Logger.e(result.exception) { "Failed to delete prompt" }
                    _uiState.value = _uiState.value.copy(
                        error = result.message ?: "Failed to delete prompt",
                    )
                }
                is Result.Loading -> { /* no-op */ }
            }
        }
    }

    fun setProductionTag(promptId: String) {
        viewModelScope.launch {
            val request = UpdatePromptTagRequest(productionPromptId = promptId)
            when (val result = promptRepository.updatePromptProductionTag(promptId, request)) {
                is Result.Success -> {
                    // Reload the selected group to reflect the new production tag
                    val groupId = _uiState.value.selectedGroup?.id
                    if (groupId != null) {
                        selectGroup(groupId)
                    }
                }
                is Result.Error -> {
                    _uiState.value = _uiState.value.copy(
                        error = result.message ?: "Failed to update production tag",
                    )
                }
                is Result.Loading -> { /* no-op */ }
            }
        }
    }

    fun dismissError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    // Share

    fun showShareDialog(groupId: String) {
        _uiState.value = _uiState.value.copy(
            showShareDialog = true,
            shareGroupId = groupId,
        )
    }

    fun dismissShareDialog() {
        _uiState.value = _uiState.value.copy(
            showShareDialog = false,
            shareGroupId = null,
        )
    }

    // Variable dialog

    fun showVariableDialog(promptTemplate: String) {
        val variables = extractPromptVariables(promptTemplate)
        if (variables.isEmpty()) return
        _uiState.value = _uiState.value.copy(
            showVariableDialog = true,
            variablePromptTemplate = promptTemplate,
            variableNames = variables,
        )
    }

    fun dismissVariableDialog() {
        _uiState.value = _uiState.value.copy(
            showVariableDialog = false,
            variablePromptTemplate = "",
            variableNames = emptyList(),
        )
    }
}

private fun PromptGroup.toDisplayData(): PromptGroupDisplayData {
    val promptText = resolvePromptText(this)
    return PromptGroupDisplayData(
        id = id ?: name,
        name = name,
        oneliner = oneliner,
        category = category,
        authorName = authorName,
        command = command,
        promptText = promptText,
    )
}

private fun PromptGroup.toDetailDisplayData(): PromptGroupDetailDisplayData {
    val promptText = resolvePromptText(this)
    return PromptGroupDetailDisplayData(
        id = id ?: name,
        name = name,
        oneliner = oneliner,
        category = category,
        authorName = authorName,
        command = command,
        productionId = productionId,
        productionPromptText = promptText,
        promptCount = prompts.size,
    )
}
