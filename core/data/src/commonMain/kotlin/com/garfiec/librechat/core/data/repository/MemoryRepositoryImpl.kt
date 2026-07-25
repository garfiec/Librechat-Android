package com.garfiec.librechat.core.data.repository

import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.common.result.safeApiCall
import com.garfiec.librechat.core.model.Memory
import com.garfiec.librechat.core.model.MemoryPreferences
import com.garfiec.librechat.core.model.request.CreateMemoryRequest
import com.garfiec.librechat.core.model.request.UpdateMemoryPreferencesRequest
import com.garfiec.librechat.core.model.request.UpdateMemoryRequest
import com.garfiec.librechat.core.network.api.MemoriesApi

class MemoryRepositoryImpl(
    private val memoriesApi: MemoriesApi,
) : MemoryRepository {

    override suspend fun getMemories(): Result<List<Memory>> =
        safeApiCall { memoriesApi.getMemories() }

    override suspend fun createMemory(request: CreateMemoryRequest): Result<Memory> =
        safeApiCall { memoriesApi.createMemory(request) }

    override suspend fun updatePreferences(request: UpdateMemoryPreferencesRequest): Result<MemoryPreferences> =
        safeApiCall { memoriesApi.updatePreferences(request) }

    override suspend fun updateMemory(
        key: String,
        request: UpdateMemoryRequest,
        agentId: String?,
    ): Result<Memory> =
        safeApiCall { memoriesApi.updateMemory(key, request, agentId) }

    override suspend fun deleteMemory(key: String, agentId: String?): Result<Unit> =
        safeApiCall { memoriesApi.deleteMemory(key, agentId) }
}
