package com.garfiec.librechat.feature.conversations.viewmodel

import co.touchlab.kermit.Logger
import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.data.repository.ProjectRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Shared Chat Projects CRUD (create/rename/delete) used by both [ProjectsViewModel] (the browse-all
 * index) and the drawer's NavHostViewModel folder section. Each host wires its own reload and error
 * surface: [onChanged] re-loads the host's project list after create/rename, [onDeleted] does the same
 * for a delete while also letting the host drop any per-project view state (expanded folders, inline
 * chats), and [emitError] routes the failure to the host's one-shot event type.
 */
class ProjectActionsDelegate(
    private val scope: CoroutineScope,
    private val projectRepository: ProjectRepository,
    private val onChanged: suspend () -> Unit,
    private val onDeleted: suspend (projectId: String) -> Unit,
    private val emitError: suspend (message: String) -> Unit,
) {

    fun create(name: String) {
        scope.launch {
            when (val result = projectRepository.createProject(name)) {
                is Result.Success -> onChanged()
                is Result.Error -> {
                    Logger.e(result.exception) { "Failed to create project" }
                    emitError("Failed to create project")
                }
                is Result.Loading -> Unit
            }
        }
    }

    fun rename(projectId: String, name: String) {
        scope.launch {
            when (val result = projectRepository.updateProject(projectId, name = name)) {
                is Result.Success -> onChanged()
                is Result.Error -> {
                    Logger.e(result.exception) { "Failed to rename project" }
                    emitError("Failed to rename project")
                }
                is Result.Loading -> Unit
            }
        }
    }

    fun delete(projectId: String) {
        scope.launch {
            when (val result = projectRepository.deleteProject(projectId)) {
                is Result.Success -> onDeleted(projectId)
                is Result.Error -> {
                    Logger.e(result.exception) { "Failed to delete project" }
                    emitError("Failed to delete project")
                }
                is Result.Loading -> Unit
            }
        }
    }
}
