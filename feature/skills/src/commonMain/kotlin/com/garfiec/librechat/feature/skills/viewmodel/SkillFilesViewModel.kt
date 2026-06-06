package com.garfiec.librechat.feature.skills.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.data.repository.RoleRepository
import com.garfiec.librechat.core.data.repository.SkillsRepository
import com.garfiec.librechat.core.model.SkillFile
import com.garfiec.librechat.core.model.permissions.Permission
import com.garfiec.librechat.core.model.permissions.PermissionType
import com.garfiec.librechat.core.model.permissions.hasAccessStrictOrDenied
import com.garfiec.librechat.feature.skills.components.PickedDocument
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@Immutable
data class SkillFilesUiState(
    val files: List<SkillFile> = emptyList(),
    val isLoading: Boolean = false,
    val isUploading: Boolean = false,
    val error: String? = null,
    /** Fail-CLOSED: file mutations require SKILLS.CREATE (upstream uploads are ACL EDIT
     *  behind checkSkillCreate). Hidden unless explicitly granted. */
    val canEditFiles: Boolean = false,
)

class SkillFilesViewModel(
    private val skillsRepository: SkillsRepository,
    private val roleRepository: RoleRepository,
    private val skillId: String,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SkillFilesUiState())
    val uiState: StateFlow<SkillFilesUiState> = _uiState.asStateFlow()

    init {
        observeEditPermission()
    }

    private fun observeEditPermission() {
        viewModelScope.launch {
            roleRepository.userPermissions.collect { role ->
                _uiState.value = _uiState.value.copy(
                    canEditFiles = role.hasAccessStrictOrDenied(PermissionType.SKILLS, Permission.CREATE),
                )
            }
        }
    }

    fun load() {
        viewModelScope.launch {
            val showSpinner = _uiState.value.files.isEmpty()
            _uiState.value = _uiState.value.copy(isLoading = showSpinner, error = null)
            when (val result = skillsRepository.listSkillFiles(skillId)) {
                is Result.Success ->
                    _uiState.value = _uiState.value.copy(files = result.data, isLoading = false)
                is Result.Error ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = result.message ?: "Failed to load files",
                    )
                is Result.Loading -> { /* no-op */ }
            }
        }
    }

    fun upload(doc: PickedDocument) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isUploading = true, error = null)
            // relativePath is the (sanitized) filename — flat, no folders. The
            // server rejects SKILL.md, absolute paths, and traversal with 400,
            // surfaced via the repo's validation-message extraction.
            val relativePath = sanitizeRelativePath(doc.filename)
            when (
                val result = skillsRepository.uploadSkillFile(
                    skillId = skillId,
                    relativePath = relativePath,
                    bytes = doc.bytes,
                    filename = doc.filename,
                    mimeType = doc.mimeType,
                )
            ) {
                is Result.Success -> {
                    _uiState.value = _uiState.value.copy(isUploading = false)
                    load()
                }
                is Result.Error ->
                    _uiState.value = _uiState.value.copy(
                        isUploading = false,
                        error = result.message ?: "Failed to upload file",
                    )
                is Result.Loading -> { /* no-op */ }
            }
        }
    }

    fun delete(file: SkillFile) {
        viewModelScope.launch {
            when (val result = skillsRepository.deleteSkillFile(skillId, file.relativePath)) {
                is Result.Success -> load()
                is Result.Error ->
                    _uiState.value = _uiState.value.copy(error = result.message ?: "Failed to delete file")
                is Result.Loading -> { /* no-op */ }
            }
        }
    }

    fun dismissError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    private fun sanitizeRelativePath(filename: String): String {
        // Keep only the basename, strip leading dots/slashes, allow the server's
        // [a-zA-Z0-9._-/] charset (we never produce folders, so replace others).
        val base = filename.substringAfterLast('/').substringAfterLast('\\').ifBlank { "file" }
        return base.map { c -> if (c.isLetterOrDigit() || c in "._-") c else '_' }.joinToString("")
            .trimStart('.')
            .ifBlank { "file" }
    }
}
