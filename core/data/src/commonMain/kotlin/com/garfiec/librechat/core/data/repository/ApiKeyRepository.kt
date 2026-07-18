package com.garfiec.librechat.core.data.repository

import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.model.ApiKey
import com.garfiec.librechat.core.model.request.CreateApiKeyRequest

interface ApiKeyRepository {
    suspend fun createApiKey(request: CreateApiKeyRequest): Result<ApiKey>
    suspend fun listApiKeys(): Result<List<ApiKey>>
    suspend fun deleteApiKey(id: String): Result<Unit>
}
