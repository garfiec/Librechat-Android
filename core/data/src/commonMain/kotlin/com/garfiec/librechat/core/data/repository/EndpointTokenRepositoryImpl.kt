package com.garfiec.librechat.core.data.repository

import com.garfiec.librechat.core.common.BackendVersion
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
    private val configRepository: ConfigRepository,
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
    ): Result<ContextUsage?> {
        // Upstream #13953 (0.8.8 line) REMOVED POST /api/endpoints/context-projection and
        // moved the gauge to a client-side / SSE-seeded computation. On such a server the POST 404s,
        // so skip it there and let the live `on_context_usage` SSE + token-config seed own the gauge
        // (a null result leaves any existing reading in place). This inverts the earlier gate that
        // enabled the projection at >= 0.8.7. Date fallback covers untagged dev builds that still
        // report 0.8.7 (see BackendVersion.supportsFeature).
        //
        // landedDate is the day AFTER the removal (which landed 2026-06-25), not the removal's own
        // day: the date gate has day granularity, so three commits sharing 2026-06-25 precede the
        // removal and would be wrongly classified as post-removal. Rounding up misclassifies the
        // other way instead — same-day post-removal builds still issue the POST and get a 404,
        // which safeApiCall turns into an error the caller ignores. That is the harmless direction.
        if (BackendVersion.supportsFeature(
                configRepository.detectedBackend.value,
                minVersion = "0.8.8-rc1",
                landedDate = "2026-06-26",
            )
        ) {
            return Result.Success(null)
        }
        return safeApiCall { endpointTokenApi.getContextProjection(request) }
    }

    override suspend fun clear() {
        tokenConfigMutex.withLock { cachedTokenConfig = null }
    }
}
