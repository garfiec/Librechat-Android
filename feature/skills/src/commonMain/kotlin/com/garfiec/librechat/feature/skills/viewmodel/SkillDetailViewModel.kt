package com.garfiec.librechat.feature.skills.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.data.repository.RoleRepository
import com.garfiec.librechat.core.data.repository.SkillsRepository
import com.garfiec.librechat.core.model.Skill
import com.garfiec.librechat.core.model.permissions.Permission
import com.garfiec.librechat.core.model.permissions.PermissionType
import com.garfiec.librechat.core.model.permissions.hasAccessStrictOrDenied
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@Immutable
data class SkillDetailUiState(
    val skill: Skill? = null,
    val isLoading: Boolean = false,
    val isDeleting: Boolean = false,
    val error: String? = null,
    val showSource: Boolean = false,
    val showDeleteConfirm: Boolean = false,
    /** Fail-CLOSED mutation gates. CREATE permission gates edit+delete (upstream
     *  checkSkillCreate = USE+CREATE); per-resource ACL is enforced server-side. */
    val canEdit: Boolean = false,
    val canDelete: Boolean = false,
    /** Per-user active override for THIS skill. Defaults active (the states map
     *  only stores explicit overrides; absent ⇒ active). */
    val isActive: Boolean = true,
    /** True once the full states map has loaded. The active Switch stays
     *  DISABLED until then: POST /skills/active is a FULL REPLACE, so toggling
     *  before the snapshot loads (or after a failed fetch) would POST a map
     *  built from emptyMap() and wipe the user's other skill overrides. */
    val activeStateLoaded: Boolean = false,
)

sealed interface SkillDetailEvent {
    data object Deleted : SkillDetailEvent
}

class SkillDetailViewModel(
    private val skillsRepository: SkillsRepository,
    private val roleRepository: RoleRepository,
    private val skillId: String,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SkillDetailUiState())
    val uiState: StateFlow<SkillDetailUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<SkillDetailEvent>()
    val events: SharedFlow<SkillDetailEvent> = _events.asSharedFlow()

    /** Full per-user active-state map; merged on toggle so we don't clobber other
     *  skills' overrides. null until loaded. */
    private var statesSnapshot: Map<String, Boolean>? = null

    init {
        observeMutationPermissions()
        // Loading is driven by the screen's ON_RESUME effect so the detail shows
        // post-edit content on return from the editor (Nav3 retains this VM, so
        // init runs only once). See SkillDetailScreen.
    }

    private fun observeMutationPermissions() {
        viewModelScope.launch {
            roleRepository.userPermissions.collect { role ->
                val canMutate = role.hasAccessStrictOrDenied(PermissionType.SKILLS, Permission.CREATE)
                _uiState.value = _uiState.value.copy(canEdit = canMutate, canDelete = canMutate)
            }
        }
    }

    fun load() {
        viewModelScope.launch {
            // Only show the full-screen spinner on the first load; a refresh-on-
            // return re-fetches in place without flashing over existing content.
            val showSpinner = _uiState.value.skill == null
            _uiState.value = _uiState.value.copy(isLoading = showSpinner, error = null)
            when (val result = skillsRepository.getSkill(skillId)) {
                is Result.Success ->
                    _uiState.value = _uiState.value.copy(skill = result.data, isLoading = false)
                is Result.Error ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = result.message ?: "Failed to load skill",
                    )
                is Result.Loading -> { /* no-op */ }
            }
        }
        loadActiveState()
    }

    /**
     * Best-effort: a denied/empty states fetch leaves the default-active view AND
     * keeps [SkillDetailUiState.activeStateLoaded] false, so the Switch stays
     * disabled — we must never POST without the real snapshot (full-replace would
     * clobber other skills' overrides).
     */
    private fun loadActiveState() {
        viewModelScope.launch {
            when (val result = skillsRepository.getSkillStates()) {
                is Result.Success -> {
                    statesSnapshot = result.data
                    _uiState.value = _uiState.value.copy(
                        isActive = result.data[skillId] ?: true,
                        activeStateLoaded = true,
                    )
                }
                is Result.Error, is Result.Loading -> { /* keep default-active + disabled */ }
            }
        }
    }

    /**
     * Optimistically flips this skill's active override and persists the merged
     * states map; rolls back on failure. Low-value on mobile today (no runtime
     * `$skill` popover) but kept correct for settings parity.
     *
     * Guarded: no-ops until the full states snapshot has loaded. POST /skills/active
     * is a FULL REPLACE, so toggling on a null snapshot would persist a map built
     * from emptyMap() and wipe every other skill's override. The UI also disables
     * the Switch on [SkillDetailUiState.activeStateLoaded] == false; this guard is
     * the belt-and-suspenders.
     */
    fun toggleActive() {
        val snapshot = statesSnapshot ?: return
        val previous = _uiState.value.isActive
        val next = !previous
        _uiState.value = _uiState.value.copy(isActive = next)
        viewModelScope.launch {
            val merged = snapshot.toMutableMap().apply { this[skillId] = next }
            when (val result = skillsRepository.updateSkillStates(merged)) {
                is Result.Success -> {
                    statesSnapshot = result.data
                    _uiState.value = _uiState.value.copy(isActive = result.data[skillId] ?: next)
                }
                is Result.Error -> {
                    // Roll back the optimistic flip.
                    _uiState.value = _uiState.value.copy(
                        isActive = previous,
                        error = result.message ?: "Failed to update skill state",
                    )
                }
                is Result.Loading -> { /* no-op */ }
            }
        }
    }

    fun toggleSource() {
        _uiState.value = _uiState.value.copy(showSource = !_uiState.value.showSource)
    }

    fun requestDelete() {
        _uiState.value = _uiState.value.copy(showDeleteConfirm = true)
    }

    fun dismissDelete() {
        _uiState.value = _uiState.value.copy(showDeleteConfirm = false)
    }

    fun confirmDelete() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isDeleting = true, showDeleteConfirm = false)
            when (val result = skillsRepository.deleteSkill(skillId)) {
                is Result.Success -> {
                    _uiState.value = _uiState.value.copy(isDeleting = false)
                    _events.emit(SkillDetailEvent.Deleted)
                }
                is Result.Error ->
                    _uiState.value = _uiState.value.copy(
                        isDeleting = false,
                        error = result.message ?: "Failed to delete skill",
                    )
                is Result.Loading -> { /* no-op */ }
            }
        }
    }
}
