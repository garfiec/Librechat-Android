package com.garfiec.librechat.core.data.repository

import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.model.ChatProject
import com.garfiec.librechat.core.model.response.ProjectListResponse

/**
 * Chat Projects (folders) repository (v0.8.7). Server is the sole source of
 * truth — no local caching (mirrors [McpRepository]/[MemoryRepository]).
 */
interface ProjectRepository {
    suspend fun listProjects(cursor: String? = null): Result<ProjectListResponse>
    suspend fun createProject(name: String, description: String? = null): Result<ChatProject>
    suspend fun updateProject(
        projectId: String,
        name: String? = null,
        description: String? = null,
    ): Result<ChatProject>
    suspend fun deleteProject(projectId: String): Result<Unit>

    /** Assigns [conversationId] to [projectId], or unassigns it when [projectId] is null. */
    suspend fun assignConversation(conversationId: String, projectId: String?): Result<Unit>
}
