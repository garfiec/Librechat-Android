package com.garfiec.librechat.core.network.api

import com.garfiec.librechat.core.model.ChatProject
import com.garfiec.librechat.core.model.request.AssignConversationToProjectRequest
import com.garfiec.librechat.core.model.request.CreateChatProjectRequest
import com.garfiec.librechat.core.model.request.UpdateChatProjectRequest
import com.garfiec.librechat.core.model.response.AssignConversationToProjectResponse
import com.garfiec.librechat.core.model.response.DeleteChatProjectResponse
import com.garfiec.librechat.core.model.response.ProjectListResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.path

/**
 * Chat Projects (folders) API (v0.8.7). Unlike the conversation mutation
 * endpoints, the project bodies are NOT `arg`-wrapped — the routes read
 * `req.body` fields directly.
 */
class ProjectsApi(
    private val client: HttpClient,
) {
    suspend fun listProjects(
        cursor: String? = null,
        limit: Int = 25,
        sortBy: String? = null,
        sortDirection: String? = null,
        search: String? = null,
    ): ProjectListResponse =
        client.get {
            url { path("api/projects") }
            parameter("cursor", cursor)
            parameter("limit", limit)
            sortBy?.let { parameter("sortBy", it) }
            sortDirection?.let { parameter("sortDirection", it) }
            search?.let { parameter("search", it) }
        }.body()

    suspend fun createProject(name: String, description: String? = null): ChatProject =
        client.post {
            url { path("api/projects") }
            setBody(CreateChatProjectRequest(name = name, description = description))
        }.body()

    suspend fun updateProject(
        projectId: String,
        name: String? = null,
        description: String? = null,
    ): ChatProject =
        client.patch {
            url { path("api/projects/$projectId") }
            setBody(UpdateChatProjectRequest(name = name, description = description))
        }.body()

    suspend fun deleteProject(projectId: String): DeleteChatProjectResponse =
        client.delete {
            url { path("api/projects/$projectId") }
        }.body()

    suspend fun assignConversation(
        conversationId: String,
        projectId: String?,
    ): AssignConversationToProjectResponse =
        client.put {
            url { path("api/projects/conversations/$conversationId") }
            setBody(AssignConversationToProjectRequest(projectId = projectId))
        }.body()
}
