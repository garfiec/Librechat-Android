package com.garfiec.librechat.core.network.api

import com.garfiec.librechat.core.model.request.ContextProjectionRequest
import com.garfiec.librechat.core.model.usage.ContextUsage
import com.garfiec.librechat.core.model.usage.ModelTokenomics
import io.ktor.client.HttpClient
import io.ktor.client.call.NoTransformationFoundException
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.path

/**
 * Token/context endpoints powering the context-usage gauge (v0.8.7).
 *
 * - `GET /api/endpoints/token-config` → per-endpoint, per-model context windows
 *   (and pricing when `interface.contextCost` is on).
 * - `POST /api/endpoints/context-projection` → a server-computed context-usage
 *   snapshot for a branch with no live snapshot (page load, model/window switch).
 *   The server may legitimately return `null`.
 */
class EndpointTokenApi(
    private val client: HttpClient,
) {
    suspend fun getTokenConfig(): Map<String, Map<String, ModelTokenomics>> =
        client.get {
            url { path("api/endpoints/token-config") }
        }.body()

    suspend fun getContextProjection(request: ContextProjectionRequest): ContextUsage? =
        try {
            client.post {
                url { path("api/endpoints/context-projection") }
                setBody(request)
            }.body()
        } catch (ignored: NoTransformationFoundException) {
            // The server signals "no snapshot" with a 204 / empty body as well as a JSON `null`.
            // body<ContextUsage?>() yields null only for a literal `null` body and throws on an
            // empty one, so treat that throw as the same no-snapshot result (deliberately swallowed).
            null
        }
}
