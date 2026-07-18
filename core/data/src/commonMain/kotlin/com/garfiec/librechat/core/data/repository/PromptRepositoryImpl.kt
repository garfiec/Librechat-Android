package com.garfiec.librechat.core.data.repository

import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.common.result.safeApiCall
import com.garfiec.librechat.core.model.Prompt
import com.garfiec.librechat.core.model.PromptGroup
import com.garfiec.librechat.core.model.request.AddPromptToGroupRequest
import com.garfiec.librechat.core.model.request.CreatePromptRequest
import com.garfiec.librechat.core.model.request.UpdatePromptGroupRequest
import com.garfiec.librechat.core.model.request.UpdatePromptTagRequest
import com.garfiec.librechat.core.model.response.PromptGroupListResponse
import com.garfiec.librechat.core.network.api.PromptsApi

class PromptRepositoryImpl(
    private val promptsApi: PromptsApi,
) : PromptRepository {

    override suspend fun getGroups(pageSize: Int, cursor: String?): Result<PromptGroupListResponse> {
        return safeApiCall {
            promptsApi.getPromptGroups(pageSize = pageSize, cursor = cursor)
        }
    }

    override suspend fun getGroup(groupId: String): Result<PromptGroup> {
        return safeApiCall {
            promptsApi.getPromptGroup(groupId)
        }
    }

    override suspend fun create(request: CreatePromptRequest): Result<PromptGroup> {
        return safeApiCall {
            promptsApi.createPrompt(request)
        }
    }

    override suspend fun update(groupId: String, request: UpdatePromptGroupRequest): Result<PromptGroup> {
        return safeApiCall {
            promptsApi.updatePromptGroup(groupId, request)
        }
    }

    override suspend fun delete(groupId: String): Result<Unit> {
        return safeApiCall {
            promptsApi.deletePromptGroup(groupId)
        }
    }

    override suspend fun addPromptToGroup(groupId: String, request: AddPromptToGroupRequest): Result<Prompt> {
        return safeApiCall {
            promptsApi.addPromptToGroup(groupId, request)
        }
    }

    override suspend fun updatePromptProductionTag(promptId: String, request: UpdatePromptTagRequest): Result<Prompt> {
        return safeApiCall {
            promptsApi.updatePromptProductionTag(promptId, request)
        }
    }

    override suspend fun getPromptsByGroupId(groupId: String): Result<List<Prompt>> {
        return safeApiCall {
            promptsApi.getPromptsByGroupId(groupId)
        }
    }

    override suspend fun recordPromptGroupUse(groupId: String): Result<Unit> {
        return safeApiCall {
            promptsApi.recordPromptGroupUse(groupId)
            Unit
        }
    }
}
