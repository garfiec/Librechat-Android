package com.garfiec.librechat.core.network.api

import com.garfiec.librechat.core.model.Prompt
import com.garfiec.librechat.core.model.PromptGroup
import com.garfiec.librechat.core.model.request.AddPromptToGroupRequest
import com.garfiec.librechat.core.model.request.CreatePromptRequest
import com.garfiec.librechat.core.model.request.UpdatePromptGroupRequest
import com.garfiec.librechat.core.model.request.UpdatePromptTagRequest
import com.garfiec.librechat.core.model.response.PromptGroupListResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.path
import kotlinx.serialization.Serializable

/**
 * Response payload for `POST /api/prompts/groups/:id/use`.
 * Added in upstream v0.8.5 for server-side usage analytics.
 */
@Serializable
data class PromptUseResponse(
    val numberOfGenerations: Int = 0,
)

class PromptsApi constructor(
    private val client: HttpClient,
) {
    suspend fun getPromptGroups(
        pageSize: Int = 10,
        cursor: String? = null,
        name: String? = null,
        category: String? = null,
    ): PromptGroupListResponse =
        client.get {
            url { path("api/prompts/groups") }
            parameter("pageSize", pageSize)
            cursor?.let { parameter("cursor", it) }
            name?.let { parameter("name", it) }
            category?.let { parameter("category", it) }
        }.body()

    suspend fun getPromptGroup(groupId: String): PromptGroup =
        client.get {
            url { path("api/prompts/groups/$groupId") }
        }.body()

    suspend fun createPrompt(prompt: CreatePromptRequest): PromptGroup =
        client.post {
            url { path("api/prompts") }
            setBody(prompt)
        }.body()

    suspend fun updatePromptGroup(groupId: String, update: UpdatePromptGroupRequest): PromptGroup =
        client.patch {
            url { path("api/prompts/groups/$groupId") }
            setBody(update)
        }.body()

    suspend fun deletePromptGroup(groupId: String) {
        client.delete {
            url { path("api/prompts/groups/$groupId") }
        }
    }

    /**
     * Add a prompt to an existing group.
     */
    suspend fun addPromptToGroup(groupId: String, request: AddPromptToGroupRequest): Prompt =
        client.post {
            url { path("api/prompts/groups/$groupId/prompts") }
            setBody(request)
        }.body()

    /**
     * Update the production tag for a prompt.
     */
    suspend fun updatePromptProductionTag(promptId: String, request: UpdatePromptTagRequest): Prompt =
        client.patch {
            url { path("api/prompts/$promptId/tags/production") }
            setBody(request)
        }.body()

    /**
     * Get all prompts belonging to a group.
     * Backend returns a raw JSON array of Prompt objects.
     */
    suspend fun getPromptsByGroupId(groupId: String): List<Prompt> =
        client.get {
            url { path("api/prompts") }
            parameter("groupId", groupId)
        }.body()

    /**
     * Records a prompt-group usage event for analytics (v0.8.5+).
     * Fire-and-forget — callers should not block UI on the response.
     */
    suspend fun recordPromptGroupUse(groupId: String): PromptUseResponse =
        client.post {
            url { path("api/prompts/groups/$groupId/use") }
        }.body()
}
