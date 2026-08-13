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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class PromptRepositoryImpl(
    private val promptsApi: PromptsApi,
) : PromptRepository {

    private val _revision = MutableStateFlow(0L)
    override val revision: StateFlow<Long> = _revision.asStateFlow()

    /** Inside `safeApiCall` and after the call, so a rejected mutation never announces a change. */
    private fun bumpRevision() {
        _revision.value += 1
    }

    override suspend fun getGroups(pageSize: Int, cursor: String?): Result<PromptGroupListResponse> {
        return safeApiCall {
            promptsApi.getPromptGroups(pageSize = pageSize, cursor = cursor)
        }
    }

    override suspend fun getAllGroups(): Result<List<PromptGroup>> {
        return safeApiCall {
            promptsApi.getAllPromptGroups()
        }
    }

    override suspend fun getGroup(groupId: String): Result<PromptGroup> {
        return safeApiCall {
            promptsApi.getPromptGroup(groupId)
        }
    }

    override suspend fun create(request: CreatePromptRequest): Result<PromptGroup> {
        return safeApiCall {
            promptsApi.createPrompt(request).also { bumpRevision() }
        }
    }

    override suspend fun update(groupId: String, request: UpdatePromptGroupRequest): Result<PromptGroup> {
        return safeApiCall {
            promptsApi.updatePromptGroup(groupId, request).also { bumpRevision() }
        }
    }

    override suspend fun delete(groupId: String): Result<Unit> {
        return safeApiCall {
            promptsApi.deletePromptGroup(groupId)
            bumpRevision()
        }
    }

    override suspend fun addPromptToGroup(groupId: String, request: AddPromptToGroupRequest): Result<Prompt> {
        return safeApiCall {
            promptsApi.addPromptToGroup(groupId, request).also { bumpRevision() }
        }
    }

    override suspend fun updatePromptProductionTag(promptId: String, request: UpdatePromptTagRequest): Result<Prompt> {
        return safeApiCall {
            promptsApi.updatePromptProductionTag(promptId, request).also { bumpRevision() }
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
