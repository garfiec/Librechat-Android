package com.garfiec.librechat.feature.skills.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.data.repository.PermissionsRepository
import com.garfiec.librechat.core.data.repository.RoleRepository
import com.garfiec.librechat.core.model.AccessRole
import com.garfiec.librechat.core.model.Principal
import com.garfiec.librechat.core.model.ResourceType
import com.garfiec.librechat.core.model.UpdateResourcePermissionsRequest
import com.garfiec.librechat.core.model.permissions.Permission
import com.garfiec.librechat.core.model.permissions.PermissionType
import com.garfiec.librechat.core.model.permissions.hasAccessStrictOrDenied
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SkillAclUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val principals: List<Principal> = emptyList(),
    val isPublic: Boolean = false,
    val publicAccessRoleId: String? = null,
    val availableRoles: List<AccessRole> = emptyList(),
    val showGrantDialog: Boolean = false,
    val searchQuery: String = "",
    val searchResults: List<Principal> = emptyList(),
    val isSearching: Boolean = false,
    /** Fail-CLOSED: section is shown only when SKILLS.SHARE is explicitly granted. */
    val canShare: Boolean = false,
    /** Fail-CLOSED: the public-share toggle is offered only with SKILLS.SHARE_PUBLIC. */
    val canSharePublic: Boolean = false,
)

/**
 * Skills-local ACL sharing viewmodel. Mirrors the agent ACL flow but
 * parameterized to [ResourceType.SKILL], reusing the SAME shared
 * [PermissionsRepository] / permissions API / [Principal] / [AccessRole]. No
 * dependency on feature/agents (features depend on :core:* only). Lifting the
 * agent + skill variants into one shared component is a potential future refactor.
 */
class SkillAclViewModel(
    private val permissionsRepository: PermissionsRepository,
    private val roleRepository: RoleRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SkillAclUiState())
    val uiState: StateFlow<SkillAclUiState> = _uiState.asStateFlow()

    private var currentSkillId: String? = null
    private var searchJob: Job? = null

    init {
        observeSharePermissions()
    }

    private fun observeSharePermissions() {
        viewModelScope.launch {
            roleRepository.userPermissions.collect { role ->
                _uiState.value = _uiState.value.copy(
                    canShare = role.hasAccessStrictOrDenied(PermissionType.SKILLS, Permission.SHARE),
                    canSharePublic = role.hasAccessStrictOrDenied(PermissionType.SKILLS, Permission.SHARE_PUBLIC),
                )
            }
        }
    }

    fun load(skillId: String) {
        currentSkillId = skillId
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)

            val rolesResult = permissionsRepository.getResourceRoles(ResourceType.SKILL)
            val roles = (rolesResult as? Result.Success)?.data ?: emptyList()

            when (val grantsResult = permissionsRepository.getResourcePermissions(ResourceType.SKILL, skillId)) {
                is Result.Success -> {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        principals = grantsResult.data.principals,
                        isPublic = grantsResult.data.public,
                        publicAccessRoleId = grantsResult.data.publicAccessRoleId,
                        availableRoles = roles,
                        error = null,
                    )
                }
                is Result.Error -> {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        availableRoles = roles,
                        error = grantsResult.message,
                    )
                }
                is Result.Loading -> Unit
            }
        }
    }

    fun openGrantDialog() {
        _uiState.value = _uiState.value.copy(showGrantDialog = true, searchQuery = "", searchResults = emptyList())
    }

    fun dismissGrantDialog() {
        _uiState.value = _uiState.value.copy(showGrantDialog = false)
    }

    fun onSearchQueryChanged(query: String) {
        _uiState.value = _uiState.value.copy(searchQuery = query)
        searchJob?.cancel()
        if (query.isBlank()) {
            _uiState.value = _uiState.value.copy(searchResults = emptyList(), isSearching = false)
            return
        }
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            _uiState.value = _uiState.value.copy(isSearching = true)
            when (val r = permissionsRepository.searchPrincipals(query)) {
                is Result.Success -> _uiState.value = _uiState.value.copy(searchResults = r.data, isSearching = false)
                is Result.Error -> {
                    Logger.d(r.exception) { "searchPrincipals failed: ${r.message}" }
                    _uiState.value = _uiState.value.copy(isSearching = false)
                }
                is Result.Loading -> Unit
            }
        }
    }

    fun grant(principal: Principal, accessRoleId: String) {
        val skillId = currentSkillId ?: return
        val granted = principal.copy(accessRoleId = accessRoleId)
        viewModelScope.launch {
            applyUpdate(
                skillId,
                UpdateResourcePermissionsRequest(
                    updated = listOf(granted),
                    removed = emptyList(),
                    public = _uiState.value.isPublic,
                    publicAccessRoleId = _uiState.value.publicAccessRoleId,
                ),
                dismissDialogOnSuccess = true,
            )
        }
    }

    fun revoke(principal: Principal) {
        val skillId = currentSkillId ?: return
        viewModelScope.launch {
            applyUpdate(
                skillId,
                UpdateResourcePermissionsRequest(
                    updated = emptyList(),
                    removed = listOf(principal),
                    public = _uiState.value.isPublic,
                    publicAccessRoleId = _uiState.value.publicAccessRoleId,
                ),
                dismissDialogOnSuccess = false,
            )
        }
    }

    fun setPublic(enabled: Boolean, accessRoleId: String?) {
        val skillId = currentSkillId ?: return
        viewModelScope.launch {
            applyUpdate(
                skillId,
                UpdateResourcePermissionsRequest(
                    updated = emptyList(),
                    removed = emptyList(),
                    public = enabled,
                    publicAccessRoleId = accessRoleId,
                ),
                dismissDialogOnSuccess = false,
            )
        }
    }

    fun dismissError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    private suspend fun applyUpdate(
        skillId: String,
        body: UpdateResourcePermissionsRequest,
        dismissDialogOnSuccess: Boolean,
    ) {
        when (val r = permissionsRepository.updateResourcePermissions(ResourceType.SKILL, skillId, body)) {
            is Result.Success -> {
                _uiState.value = _uiState.value.copy(
                    principals = r.data.principals,
                    isPublic = r.data.public,
                    publicAccessRoleId = r.data.publicAccessRoleId,
                    showGrantDialog = if (dismissDialogOnSuccess) false else _uiState.value.showGrantDialog,
                    error = null,
                )
            }
            is Result.Error -> _uiState.value = _uiState.value.copy(error = r.message)
            is Result.Loading -> Unit
        }
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 300L
    }
}
