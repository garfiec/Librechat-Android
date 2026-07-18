package com.garfiec.librechat.core.network.api

import co.touchlab.kermit.Logger
import com.garfiec.librechat.core.model.Agent
import com.garfiec.librechat.core.model.AgentAction
import com.garfiec.librechat.core.model.AgentCategory
import com.garfiec.librechat.core.model.AgentTool
import com.garfiec.librechat.core.model.request.CreateActionRequest
import com.garfiec.librechat.core.model.request.CreateAgentRequest
import com.garfiec.librechat.core.model.request.RevertAgentRequest
import com.garfiec.librechat.core.model.request.UpdateAgentRequest
import com.garfiec.librechat.core.model.response.AgentListResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.path
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

class AgentsApi constructor(
    private val client: HttpClient,
) {

    // --- Agent CRUD ---

    suspend fun getAgents(category: String? = null): AgentListResponse =
        client.get {
            url { path("api/agents") }
            if (category != null) parameter("category", category)
        }.body()

    suspend fun getAgentsPaginated(
        pageNumber: Int,
        pageSize: Int,
        search: String? = null,
        category: String? = null,
    ): AgentListResponse =
        client.get {
            url { path("api/agents") }
            parameter("pageNumber", pageNumber)
            parameter("pageSize", pageSize)
            if (search != null) parameter("search", search)
            if (category != null) parameter("category", category)
        }.body()

    suspend fun getAgent(agentId: String): Agent {
        Logger.d("AgentsApi") { "getAgent($agentId) - using VIEW endpoint" }
        return client.get {
            url { path("api/agents/$agentId") }
        }.body()
    }

    /**
     * Fetches the full agent data for editing via the /expanded endpoint.
     *
     * The standard GET /api/agents/:id endpoint returns only basic view fields
     * (id, name, description, avatar, provider, model, projectIds, isCollaborative,
     * isPublic, version, createdAt, updatedAt). It does NOT include instructions,
     * tools, category, conversation_starters, model_parameters, or other
     * configuration fields needed for editing.
     *
     * The /expanded endpoint (GET /api/agents/:id/expanded) requires EDIT permission
     * and returns the complete agent document with all fields.
     */
    suspend fun getAgentForEditing(agentId: String): Agent {
        Logger.d("AgentsApi") { "getAgentForEditing($agentId) - using EXPANDED endpoint" }
        return client.get {
            url { path("api/agents/$agentId/expanded") }
        }.body()
    }

    suspend fun createAgent(request: CreateAgentRequest): Agent =
        client.post {
            url { path("api/agents") }
            setBody(request)
        }.body()

    suspend fun updateAgent(agentId: String, request: UpdateAgentRequest): Agent =
        client.patch {
            url { path("api/agents/$agentId") }
            setBody(request)
        }.body()

    suspend fun deleteAgent(agentId: String) {
        client.delete {
            url { path("api/agents/$agentId") }
        }
    }

    suspend fun duplicateAgent(agentId: String): Agent =
        client.post {
            url { path("api/agents/$agentId/duplicate") }
        }.body()

    suspend fun revertAgent(agentId: String, request: RevertAgentRequest): Agent =
        client.post {
            url { path("api/agents/$agentId/revert") }
            setBody(request)
        }.body()

    suspend fun getAgentCategories(): List<AgentCategory> =
        client.get {
            url { path("api/agents/categories") }
        }.body()

    // --- Agent Actions ---

    suspend fun getAgentActions(): List<AgentAction> =
        client.get {
            url { path("api/agents/actions") }
        }.body()

    suspend fun addOrUpdateAction(agentId: String, request: CreateActionRequest): JsonArray =
        client.post {
            url { path("api/agents/actions/$agentId") }
            setBody(request)
        }.body()

    suspend fun deleteAction(agentId: String, actionId: String) {
        client.delete {
            url { path("api/agents/actions/$agentId/$actionId") }
        }
    }

    // --- Agent Avatar ---

    /**
     * Reset the agent's avatar to the default. Upstream's wire format is a
     * PATCH with explicit `"avatar": null` — we build the body as a raw
     * [JsonObject] so the network module's `explicitNulls = false` config
     * doesn't drop the null.
     */
    suspend fun resetAgentAvatar(agentId: String): Agent =
        client.patch {
            url { path("api/agents/$agentId") }
            setBody(JsonObject(mapOf("avatar" to JsonNull)))
        }.body()

    suspend fun uploadAgentAvatar(
        agentId: String,
        imageBytes: ByteArray,
        mimeType: String,
    ): Agent =
        client.submitFormWithBinaryData(
            formData = formData {
                append("file", imageBytes, Headers.build {
                    append(HttpHeaders.ContentDisposition, "filename=\"avatar.${mimeType.substringAfter("/")}\"")
                    append(HttpHeaders.ContentType, mimeType)
                })
            },
        ) {
            url { path("api/files/images/agents/$agentId/avatar") }
        }.body()

    // --- Agent Tools ---

    suspend fun getAvailableTools(): List<AgentTool> =
        client.get {
            url { path("api/agents/tools") }
        }.body()
}
