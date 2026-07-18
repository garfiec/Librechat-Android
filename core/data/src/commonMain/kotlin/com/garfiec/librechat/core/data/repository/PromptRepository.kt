package com.garfiec.librechat.core.data.repository

import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.model.Prompt
import com.garfiec.librechat.core.model.PromptGroup
import com.garfiec.librechat.core.model.request.AddPromptToGroupRequest
import com.garfiec.librechat.core.model.request.CreatePromptRequest
import com.garfiec.librechat.core.model.request.UpdatePromptGroupRequest
import com.garfiec.librechat.core.model.request.UpdatePromptTagRequest
import com.garfiec.librechat.core.model.response.PromptGroupListResponse

interface PromptRepository {
    suspend fun getGroups(pageSize: Int = 10, cursor: String? = null): Result<PromptGroupListResponse>
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
