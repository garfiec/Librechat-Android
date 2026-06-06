package com.garfiec.librechat.feature.settings.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.data.repository.RoleRepository
import com.garfiec.librechat.core.model.permissions.Permission
import com.garfiec.librechat.core.model.permissions.PermissionType
import com.garfiec.librechat.core.model.request.UpdateRoleSkillsRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@Immutable
data class RoleSkillsAdminUiState(
    /** Roles offered in the picker (upstream SystemRoles). */
    val roles: List<String> = listOf("USER", "ADMIN"),
    val selectedRole: String = "USER",
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val loaded: Boolean = false,
    val error: String? = null,
    val savedMessage: String? = null,
    // The role's SKILLS permission booleans.
    val use: Boolean = false,
    val create: Boolean = false,
    val share: Boolean = false,
    val sharePublic: Boolean = false,
) {
    val canSave: Boolean get() = loaded && !isSaving && !isLoading
}

/**
 * Admin-only editor for a role's SKILLS permission booleans
 * (GET /api/roles/:role → PUT /api/roles/:role/skills). The screen is reachable
 * only when the current user is ADMIN (gated in Settings); the server also
 * enforces via manageRoles. There is no per-role skill-id allowlist — these are
 * the role-level USE/CREATE/SHARE/SHARE_PUBLIC permission flags.
 */
class RoleSkillsAdminViewModel(
    private val roleRepository: RoleRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(RoleSkillsAdminUiState())
    val uiState: StateFlow<RoleSkillsAdminUiState> = _uiState.asStateFlow()

    init {
        loadRole(_uiState.value.selectedRole)
    }

    fun selectRole(role: String) {
        if (role == _uiState.value.selectedRole) return
        _uiState.value = _uiState.value.copy(selectedRole = role, loaded = false, savedMessage = null)
        loadRole(role)
    }

    fun setUse(value: Boolean) { _uiState.value = _uiState.value.copy(use = value, savedMessage = null) }
    fun setCreate(value: Boolean) { _uiState.value = _uiState.value.copy(create = value, savedMessage = null) }
    fun setShare(value: Boolean) { _uiState.value = _uiState.value.copy(share = value, savedMessage = null) }
    fun setSharePublic(value: Boolean) { _uiState.value = _uiState.value.copy(sharePublic = value, savedMessage = null) }

    fun dismissError() { _uiState.value = _uiState.value.copy(error = null) }

    private fun loadRole(role: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            when (val result = roleRepository.getRole(role)) {
                is Result.Success -> {
                    val perms = result.data
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        loaded = true,
                        use = perms.hasAccessStrict(PermissionType.SKILLS, Permission.USE),
                        create = perms.hasAccessStrict(PermissionType.SKILLS, Permission.CREATE),
                        share = perms.hasAccessStrict(PermissionType.SKILLS, Permission.SHARE),
                        sharePublic = perms.hasAccessStrict(PermissionType.SKILLS, Permission.SHARE_PUBLIC),
                    )
                }
                is Result.Error ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = result.message ?: "Failed to load role",
                    )
                is Result.Loading -> { /* no-op */ }
            }
        }
    }

    fun save() {
        val state = _uiState.value
        if (!state.canSave) return
        viewModelScope.launch {
            _uiState.value = state.copy(isSaving = true, error = null, savedMessage = null)
            val request = UpdateRoleSkillsRequest(
                use = state.use,
                create = state.create,
                share = state.share,
                sharePublic = state.sharePublic,
            )
            when (val result = roleRepository.updateRoleSkills(state.selectedRole, request)) {
                is Result.Success -> {
                    val perms = result.data
                    _uiState.value = _uiState.value.copy(
                        isSaving = false,
                        savedMessage = "Saved",
                        // Rebase from the server's returned role so the toggles reflect
                        // exactly what was persisted (the server may normalize).
                        use = perms.hasAccessStrict(PermissionType.SKILLS, Permission.USE),
                        create = perms.hasAccessStrict(PermissionType.SKILLS, Permission.CREATE),
                        share = perms.hasAccessStrict(PermissionType.SKILLS, Permission.SHARE),
                        sharePublic = perms.hasAccessStrict(PermissionType.SKILLS, Permission.SHARE_PUBLIC),
                    )
                    // If the admin just edited their OWN role, the app-wide cached
                    // permissions are now stale (live gating elsewhere would keep using
                    // the old flags until the next session fetch). Refresh that cache.
                    if (state.selectedRole == roleRepository.userPermissions.value?.name) {
                        roleRepository.fetchUserRole()
                    }
                }
                is Result.Error ->
                    _uiState.value = _uiState.value.copy(
                        isSaving = false,
                        error = result.message ?: "Failed to save role skills",
                    )
                is Result.Loading -> { /* no-op */ }
            }
        }
    }
}
