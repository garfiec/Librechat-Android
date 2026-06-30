package com.garfiec.librechat.core.data.repository

import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.common.result.safeApiCall
import com.garfiec.librechat.core.model.request.ContextProjectionRequest
import com.garfiec.librechat.core.model.usage.ContextUsage
import com.garfiec.librechat.core.model.usage.ModelTokenomics
import com.garfiec.librechat.core.network.api.EndpointTokenApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class EndpointTokenRepositoryImpl(
    private val endpointTokenApi: EndpointTokenApi,
) : EndpointTokenRepository {

    // token-config is static within a session, so memoize it on this singleton: every
    // per-conversation ChatViewModel shares the same fetch instead of hitting the network
    // on each chat open. The mutex collapses concurrent first-callers into one request.
    private val tokenConfigMutex = Mutex()
    private var cachedTokenConfig: Map<String, Map<String, ModelTokenomics>>? = null

    override suspend fun getTokenConfig(): Result<Map<String, Map<String, ModelTokenomics>>> {
        cachedTokenConfig?.let { return Result.Success(it) }
        return tokenConfigMutex.withLock {
            cachedTokenConfig?.let { return@withLock Result.Success(it) }
            safeApiCall { endpointTokenApi.getTokenConfig() }
                .also { result -> if (result is Result.Success) cachedTokenConfig = result.data }
        }
    }

    override suspend fun getContextProjection(
        request: ContextProjectionRequest,
    ): Result<ContextUsage?> =
        safeApiCall { endpointTokenApi.getContextProjection(request) }

    override suspend fun clear() {
        tokenConfigMutex.withLock { cachedTokenConfig = null }
    }
}
