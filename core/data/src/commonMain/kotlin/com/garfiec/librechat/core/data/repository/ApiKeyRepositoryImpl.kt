package com.garfiec.librechat.core.data.repository

import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.common.result.safeApiCall
import com.garfiec.librechat.core.model.ApiKey
import com.garfiec.librechat.core.model.request.CreateApiKeyRequest
import com.garfiec.librechat.core.network.api.ApiKeysApi

class ApiKeyRepositoryImpl(
    private val apiKeysApi: ApiKeysApi,
) : ApiKeyRepository {

    override suspend fun createApiKey(request: CreateApiKeyRequest): Result<ApiKey> = safeApiCall {
        apiKeysApi.createApiKey(request)
    }

    override suspend fun listApiKeys(): Result<List<ApiKey>> = safeApiCall {
        apiKeysApi.listApiKeys()
    }

    override suspend fun deleteApiKey(id: String): Result<Unit> = safeApiCall {
        apiKeysApi.deleteApiKey(id)
    }
}
