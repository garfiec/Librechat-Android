package com.garfiec.librechat.core.data.repository

import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.common.result.safeApiCall
import com.garfiec.librechat.core.data.db.dao.ConversationDao
import com.garfiec.librechat.core.model.ChatProject
import com.garfiec.librechat.core.model.response.ProjectListResponse
import com.garfiec.librechat.core.network.api.ProjectsApi

class ProjectRepositoryImpl(
    private val projectsApi: ProjectsApi,
    private val conversationDao: ConversationDao,
) : ProjectRepository {

    override suspend fun listProjects(cursor: String?): Result<ProjectListResponse> =
        safeApiCall { projectsApi.listProjects(cursor = cursor) }

    override suspend fun createProject(name: String, description: String?): Result<ChatProject> =
        safeApiCall { projectsApi.createProject(name, description) }

    override suspend fun getProject(projectId: String): Result<ChatProject> =
        safeApiCall { projectsApi.getProject(projectId) }

    override suspend fun updateProject(
        projectId: String,
        name: String?,
        description: String?,
    ): Result<ChatProject> =
        safeApiCall { projectsApi.updateProject(projectId, name, description) }

    override suspend fun deleteProject(projectId: String): Result<Unit> =
        safeApiCall { projectsApi.deleteProject(projectId) }

    override suspend fun assignConversation(
        conversationId: String,
        projectId: String?,
    ): Result<Unit> =
        // The assign endpoint is API-only, but the cached row carries chatProjectId (read by the
        // drawer move-picker to pre-select the current folder). Mirror the new assignment into Room
        // on success so the picker self-corrects immediately instead of lagging until the next full
        // sync. projectId == null is an unassign and clears the column.
        safeApiCall<Unit> { projectsApi.assignConversation(conversationId, projectId) }
            .also { result ->
                if (result is Result.Success) {
                    conversationDao.updateChatProjectId(conversationId, projectId)
                }
            }
}
