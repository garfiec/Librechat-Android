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
import com.garfiec.librechat.core.model.request.CreatePromptData
import com.garfiec.librechat.core.model.request.CreatePromptGroupData
import com.garfiec.librechat.core.model.request.CreatePromptRequest
import com.garfiec.librechat.core.model.request.UpdatePromptTagRequest
import com.garfiec.librechat.feature.chat.prompts.components.PromptSortOrder
import com.garfiec.librechat.feature.chat.prompts.components.extractVariables
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

    init {
        observePermissionFlags()
        loadInitialGroups()
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

    fun loadGroups() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            when (val result = promptRepository.getGroups()) {
                is Result.Success -> {
                    val response = result.data
                    val groups = response.promptGroups
                    rawGroups = groups
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
                        availableCategories = categories,
                    )
                    applyFilters()
                }
                is Result.Error -> {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = result.message ?: "Failed to load prompts",
                    )
                }
                is Result.Loading -> { /* no-op */ }
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isRefreshing = true, error = null)
            when (val result = promptRepository.getGroups()) {
                is Result.Success -> {
                    val response = result.data
                    val groups = response.promptGroups
                    rawGroups = groups
                    val categories = groups
                        .mapNotNull { it.category }
                        .filter { it.isNotBlank() }
                        .distinct()
                        .sorted()

                    _uiState.value = _uiState.value.copy(
                        groups = groups.map { it.toDisplayData() },
                        totalPages = if (response.hasMore) 9999 else 1,
                        currentPage = 1,
                        isRefreshing = false,
                        availableCategories = categories,
                    )
                    applyFilters()
                }
                is Result.Error -> {
                    _uiState.value = _uiState.value.copy(
                        isRefreshing = false,
                        error = result.message ?: "Failed to refresh prompts",
                    )
                }
                is Result.Loading -> { /* no-op */ }
            }
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

    fun createPrompt(promptText: String, type: String, groupName: String) {
        viewModelScope.launch {
            val request = CreatePromptRequest(
                prompt = CreatePromptData(prompt = promptText, type = type),
                group = CreatePromptGroupData(name = groupName),
            )
            try {
                promptRepository.create(request)
                refresh()
            } catch (e: Exception) {
                Logger.e(e) { "Failed to create prompt" }
                _uiState.value = _uiState.value.copy(
                    error = "Failed to create prompt",
                )
            }
        }
    }

    fun deleteGroup(groupId: String) {
        viewModelScope.launch {
            try {
                promptRepository.delete(groupId)
                _uiState.value = _uiState.value.copy(selectedGroup = null)
                refresh()
            } catch (e: Exception) {
                Logger.e(e) { "Failed to delete prompt" }
                _uiState.value = _uiState.value.copy(
                    error = "Failed to delete prompt",
                )
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
        val variables = extractVariables(promptTemplate)
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
    // List endpoint: productionPrompt comes from $lookup
    // Detail endpoint: prompts array is populated
    val promptText = productionPrompt?.prompt
        ?: prompts.firstOrNull { it.id == productionId }?.prompt
        ?: prompts.firstOrNull()?.prompt
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
    val promptText = productionPrompt?.prompt
        ?: prompts.firstOrNull { it.id == productionId }?.prompt
        ?: prompts.firstOrNull()?.prompt
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
