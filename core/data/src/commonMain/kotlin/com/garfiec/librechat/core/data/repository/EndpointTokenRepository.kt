package com.garfiec.librechat.core.data.repository

import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.model.request.ContextProjectionRequest
import com.garfiec.librechat.core.model.usage.ContextUsage
import com.garfiec.librechat.core.model.usage.ModelTokenomics

/**
 * Token/context endpoints for the context-usage gauge (v0.8.7). The token-config map is
 * memoized for the lifetime of a session (it's static per backend), so [clear] must be
 * invoked on logout/server-switch to drop the previous server's config.
 */
interface EndpointTokenRepository {
    suspend fun getTokenConfig(): Result<Map<String, Map<String, ModelTokenomics>>>
    suspend fun getContextProjection(request: ContextProjectionRequest): Result<ContextUsage?>

    /** Drops the cached token-config so the next caller re-fetches it for the new session. */
    suspend fun clear()
}
