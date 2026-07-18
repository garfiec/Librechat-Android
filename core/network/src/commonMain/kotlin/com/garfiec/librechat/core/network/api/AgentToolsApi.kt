package com.garfiec.librechat.core.network.api

import com.garfiec.librechat.core.model.ToolAuthResult
import com.garfiec.librechat.core.network.api.dto.UpdateUserPluginAuthRequest
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.encodeURLPathPart
import io.ktor.http.path

/**
 * Tool-auth surface for the agent editor. Backed by upstream routes:
 * - `GET /api/agents/tools/:toolId/auth` -> [ToolAuthResult]
 *   (routes/agents/tools.js:24, controllers/tools.js:62)
 * - `POST /api/user/plugins` with the rich body that
 *   [UpdateUserPluginAuthRequest] models (controllers/UserController.js:167).
 */
class AgentToolsApi(
    private val client: HttpClient,
) {

    suspend fun verifyToolAuth(toolId: String): ToolAuthResult =
        client.get {
            url { path("api/agents/tools/${toolId.encodeURLPathPart()}/auth") }
        }.body()

    suspend fun installPluginAuth(request: UpdateUserPluginAuthRequest) {
        client.post {
            url { path("api/user/plugins") }
            setBody(request)
        }
    }
}
