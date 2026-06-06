package com.garfiec.librechat.feature.skills.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.data.repository.ConfigRepository
import com.garfiec.librechat.core.data.repository.SkillUpdateResult
import com.garfiec.librechat.core.data.repository.SkillsRepository
import com.garfiec.librechat.core.model.Skill
import com.garfiec.librechat.core.model.request.CreateSkillRequest
import com.garfiec.librechat.core.model.request.UpdateSkillRequest
import com.garfiec.librechat.core.model.response.Category
import com.garfiec.librechat.feature.skills.SkillValidation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@Immutable
data class SkillEditorUiState(
    val isEditMode: Boolean = false,
    val skillId: String? = null,
    val name: String = "",
    val displayTitle: String = "",
    val description: String = "",
    val body: String = "",
    val category: String = "",
    /** When true, the skill auto-primes into every turn (mirrors the upstream
     *  `always-apply` frontmatter field; server default is false). */
    val alwaysApply: Boolean = false,
    /** Server category presets (`GET /api/categories`) for the category dropdown.
     *  Empty until loaded; the dropdown falls back to whatever [category] holds. */
    val availableCategories: List<Category> = emptyList(),
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val error: String? = null,
    /** Surfaced when a save 409s: the skill changed on the server since the editor
     *  opened. The user's edits are PRESERVED (only [loadedVersion] is bumped to
     *  the server's current version); a re-save then overwrites. */
    val conflictNotice: String? = null,
    /** Version loaded into the editor; sent as expectedVersion on PATCH. Bumped to
     *  the server's current version on a 409 so a re-save targets the right one. */
    val loadedVersion: Int = 0,
    /** Authoritative server skill captured on a 409 conflict (kept for a possible
     *  future diff/discard affordance; the user's edits are NOT replaced by it). */
    val serverCurrent: Skill? = null,
) {
    val nameError: Boolean get() = name.isNotEmpty() && !SkillValidation.isNameValid(name)
    val descriptionTooLong: Boolean get() = description.length > SkillValidation.DESCRIPTION_MAX_LENGTH
    val canSave: Boolean
        get() = !isSaving &&
            SkillValidation.isNameValid(name) &&
            SkillValidation.isDescriptionValid(description) &&
            SkillValidation.isBodyValid(body)
}

sealed interface SkillEditorEvent {
    data class Saved(val skillId: String) : SkillEditorEvent
}

class SkillEditorViewModel(
    private val skillsRepository: SkillsRepository,
    private val configRepository: ConfigRepository,
    initialSkillId: String? = null,
) : ViewModel() {

    private val editId: String? = initialSkillId

    private val _uiState = MutableStateFlow(
        SkillEditorUiState(isEditMode = editId != null, skillId = editId),
    )
    val uiState: StateFlow<SkillEditorUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<SkillEditorEvent>()
    val events: SharedFlow<SkillEditorEvent> = _events.asSharedFlow()

    init {
        if (editId != null) load(editId)
        loadCategories()
    }

    private fun loadCategories() {
        viewModelScope.launch {
            val result = configRepository.getCategories()
            if (result is Result.Success) {
                _uiState.value = _uiState.value.copy(availableCategories = result.data)
            }
            // A category-list fetch failure is non-fatal: the dropdown simply shows
            // no presets and the user keeps whatever category was loaded/typed.
        }
    }

    private fun load(id: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            when (val result = skillsRepository.getSkill(id)) {
                is Result.Success -> _uiState.value = _uiState.value.applyLoaded(result.data)
                is Result.Error -> _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = result.message ?: "Failed to load skill",
                )
                is Result.Loading -> { /* no-op */ }
            }
        }
    }

    private fun SkillEditorUiState.applyLoaded(skill: Skill) = copy(
        name = skill.name,
        displayTitle = skill.displayTitle ?: "",
        description = skill.description,
        body = skill.body,
        category = skill.category ?: "",
        alwaysApply = skill.alwaysApply ?: false,
        loadedVersion = skill.version,
        isLoading = false,
    )

    fun onNameChanged(value: String) { _uiState.value = _uiState.value.copy(name = value) }
    fun onDisplayTitleChanged(value: String) { _uiState.value = _uiState.value.copy(displayTitle = value) }
    fun onDescriptionChanged(value: String) { _uiState.value = _uiState.value.copy(description = value) }
    fun onBodyChanged(value: String) { _uiState.value = _uiState.value.copy(body = value) }
    fun onCategoryChanged(value: String) { _uiState.value = _uiState.value.copy(category = value) }
    fun onAlwaysApplyChanged(value: Boolean) { _uiState.value = _uiState.value.copy(alwaysApply = value) }

    fun dismissConflictNotice() { _uiState.value = _uiState.value.copy(conflictNotice = null) }

    fun save() {
        val state = _uiState.value
        if (!state.canSave) return
        viewModelScope.launch {
            _uiState.value = state.copy(isSaving = true, error = null, conflictNotice = null)
            if (state.isEditMode && state.skillId != null) {
                saveUpdate(state)
            } else {
                saveCreate(state)
            }
        }
    }

    private suspend fun saveCreate(state: SkillEditorUiState) {
        val request = CreateSkillRequest(
            name = state.name,
            description = state.description,
            body = state.body,
            displayTitle = state.displayTitle.ifBlank { null },
            category = state.category.ifBlank { null },
            // Send the explicit boolean (not null) so the value round-trips and a
            // later edit can toggle it back off (server $set-merges on update).
            alwaysApply = state.alwaysApply,
        )
        when (val result = skillsRepository.createSkill(request)) {
            is Result.Success -> {
                _uiState.value = _uiState.value.copy(isSaving = false)
                _events.emit(SkillEditorEvent.Saved(result.data.id))
            }
            is Result.Error -> _uiState.value = _uiState.value.copy(
                isSaving = false,
                error = result.message ?: "Failed to create skill",
            )
            is Result.Loading -> { /* no-op */ }
        }
    }

    private suspend fun saveUpdate(state: SkillEditorUiState) {
        val request = UpdateSkillRequest(
            expectedVersion = state.loadedVersion,
            name = state.name,
            description = state.description,
            body = state.body,
            displayTitle = state.displayTitle.ifBlank { null },
            category = state.category.ifBlank { null },
            // Explicit boolean so toggling OFF on edit persists (server $set-merge
            // would otherwise keep a prior `true` if the field were omitted).
            alwaysApply = state.alwaysApply,
        )
        when (val result = skillsRepository.updateSkill(state.skillId!!, request)) {
            is SkillUpdateResult.Success -> {
                _uiState.value = _uiState.value.copy(isSaving = false)
                _events.emit(SkillEditorEvent.Saved(result.skill.id))
            }
            is SkillUpdateResult.Conflict -> {
                // KEEP the user's unsaved field edits — only adopt the server's new
                // version so a re-save targets the right expectedVersion. Never
                // clobber their work with server values. [serverCurrent] stashes the
                // authoritative skill for a possible future diff/discard affordance.
                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    loadedVersion = result.current.version,
                    serverCurrent = result.current,
                    conflictNotice = "This skill was changed on the server since you opened it. " +
                        "Your edits are preserved — save again to overwrite.",
                )
            }
            is SkillUpdateResult.Error -> _uiState.value = _uiState.value.copy(
                isSaving = false,
                error = result.message,
            )
        }
    }
}
