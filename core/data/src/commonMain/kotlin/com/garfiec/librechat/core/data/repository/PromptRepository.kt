package com.garfiec.librechat.core.data.repository

import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.model.Prompt
import com.garfiec.librechat.core.model.PromptGroup
import com.garfiec.librechat.core.model.request.AddPromptToGroupRequest
import com.garfiec.librechat.core.model.request.CreatePromptRequest
import com.garfiec.librechat.core.model.request.UpdatePromptGroupRequest
import com.garfiec.librechat.core.model.request.UpdatePromptTagRequest
import com.garfiec.librechat.core.model.response.PromptGroupListResponse
import kotlinx.coroutines.flow.StateFlow

interface PromptRepository {

    /**
     * Bumped after any prompt mutation this repository accepted — create, group update, delete,
     * a new version added to a group, or a production-tag change.
     *
     * Prompts are network-direct with no Room cache, so a screen that fetched them once has no
     * observable to re-read from and nothing else reports the staleness: the composer's `/` picker
     * keeps offering a deleted prompt and inserting a superseded body. Every prompt mutation in the
     * app goes through the methods below, so a mutation added there must bump here too. Mirrors
     * [AgentRepository.revision]; the consumer side is in `feature/chat/CLAUDE.md`.
     */
    val revision: StateFlow<Long>

    suspend fun getGroups(pageSize: Int = 10, cursor: String? = null): Result<PromptGroupListResponse>

    /** Every visible prompt group in one call, for surfaces that must not truncate (the `/` picker). */
    suspend fun getAllGroups(): Result<List<PromptGroup>>
    suspend fun getGroup(groupId: String): Result<PromptGroup>
    suspend fun create(request: CreatePromptRequest): Result<PromptGroup>
    suspend fun update(groupId: String, request: UpdatePromptGroupRequest): Result<PromptGroup>
    suspend fun delete(groupId: String): Result<Unit>
    suspend fun addPromptToGroup(groupId: String, request: AddPromptToGroupRequest): Result<Prompt>
    suspend fun updatePromptProductionTag(promptId: String, request: UpdatePromptTagRequest): Result<Prompt>
    suspend fun getPromptsByGroupId(groupId: String): Result<List<Prompt>>

    /**
     * Records that a prompt group was used (v0.8.5+ server-side analytics).
     * Fire-and-forget — failures are swallowed since this is telemetry only.
     */
    suspend fun recordPromptGroupUse(groupId: String): Result<Unit>
}
