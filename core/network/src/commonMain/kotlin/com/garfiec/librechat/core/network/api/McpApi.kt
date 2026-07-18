package com.garfiec.librechat.core.network.api

import com.garfiec.librechat.core.model.mcp.McpApiKeyConfig
import com.garfiec.librechat.core.model.mcp.McpApiKeySource
import com.garfiec.librechat.core.model.mcp.McpAuthorizationType
import com.garfiec.librechat.core.model.mcp.McpConnectionStatusResponse
import com.garfiec.librechat.core.model.mcp.McpOAuthConfig
import com.garfiec.librechat.core.model.mcp.McpReinitializeResponse
import com.garfiec.librechat.core.model.mcp.McpServer
import com.garfiec.librechat.core.model.mcp.McpServerStatus
import com.garfiec.librechat.core.model.mcp.McpServerType
import com.garfiec.librechat.core.model.mcp.McpTool
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class McpApi constructor(
    private val client: HttpClient,
    private val json: Json,
) {

    /**
     * GET /api/mcp/tools returns { servers: Record<string, MCPServer> }
     * where each MCPServer has: name, icon, authenticated, authConfig, tools[]
     * tools[] items have: name, pluginKey, description
     */
    suspend fun getTools(): List<McpTool> {
        val response: JsonObject = client.get {
            url { path("api/mcp/tools") }
        }.body()
        val servers = response["servers"]?.jsonObject ?: return emptyList()
        val tools = mutableListOf<McpTool>()
        for ((serverName, serverJson) in servers) {
            val serverObj = serverJson.jsonObject
            val serverTools = serverObj["tools"]?.jsonArray ?: continue
            for (toolJson in serverTools) {
                val toolObj = toolJson.jsonObject
                tools.add(
                    McpTool(
                        name = toolObj["name"]?.jsonPrimitive?.contentOrNull ?: continue,
                        description = toolObj["description"]?.jsonPrimitive?.contentOrNull,
                        serverName = serverName,
                    ),
                )
            }
        }
        return tools
    }

    suspend fun reinitialize(serverName: String): McpReinitializeResponse {
        val response = client.post {
            url { path("api/mcp/$serverName/reinitialize") }
        }
        val text = response.bodyAsText()
        if (text.trimStart().startsWith("<")) {
            return McpReinitializeResponse(success = false, message = "Server returned unexpected response")
        }
        return json.decodeFromString(text)
    }

    suspend fun getConnectionStatus(): Map<String, McpServerStatus> {
        val response: McpConnectionStatusResponse = client.get {
            url { path("api/mcp/connection/status") }
        }.body()
        return response.connectionStatus
    }

    /**
     * GET /api/mcp/servers returns Record<string, ParsedServerConfig>
     * where ParsedServerConfig has: type, url, title, description, etc.
     * The key in the map is the server name.
     */
    suspend fun listServers(): List<McpServer> {
        val response: JsonObject = client.get {
            url { path("api/mcp/servers") }
        }.body()
        return response.entries.map { (serverName, configJson) ->
            val config = configJson.jsonObject
            McpServer(
                name = serverName,
                url = config["url"]?.jsonPrimitive?.contentOrNull ?: "",
                type = parseServerType(config["type"]?.jsonPrimitive?.contentOrNull),
                title = config["title"]?.jsonPrimitive?.contentOrNull,
                description = config["description"]?.jsonPrimitive?.contentOrNull,
                apiKey = config["apiKey"]?.jsonObject?.let { parseApiKeyConfig(it) },
                oauth = config["oauth"]?.jsonObject?.let { parseOAuthConfig(it) },
            )
        }
    }

    suspend fun createServer(
        name: String,
        description: String? = null,
        url: String,
        type: McpServerType,
        apiKey: McpApiKeyConfig? = null,
        oauth: McpOAuthConfig? = null,
    ): McpServer {
        val configMap = buildMap<String, Any> {
            put("url", url)
            put("type", type.serialName)
            put("title", name)
            if (!description.isNullOrBlank()) put("description", description)
            if (apiKey != null) {
                put("apiKey", buildMap {
                    put("source", apiKey.source.serialName)
                    put("authorization_type", apiKey.authorizationType.serialName)
                    if (!apiKey.key.isNullOrBlank()) put("key", apiKey.key)
                    if (!apiKey.customHeader.isNullOrBlank()) put("custom_header", apiKey.customHeader)
                })
            }
            if (oauth != null) {
                put("oauth", buildMap {
                    if (!oauth.authorizationUrl.isNullOrBlank()) put("authorization_url", oauth.authorizationUrl)
                    if (!oauth.tokenUrl.isNullOrBlank()) put("token_url", oauth.tokenUrl)
                    if (!oauth.clientId.isNullOrBlank()) put("client_id", oauth.clientId)
                    if (!oauth.clientSecret.isNullOrBlank()) put("client_secret", oauth.clientSecret)
                    if (!oauth.scope.isNullOrBlank()) put("scope", oauth.scope)
                })
            }
        }
        val response: JsonObject = client.post {
            url { path("api/mcp/servers") }
            setBody(mapOf("config" to configMap))
        }.body()
        val serverName = response["serverName"]?.jsonPrimitive?.contentOrNull ?: name
        return McpServer(
            name = serverName,
            url = response["url"]?.jsonPrimitive?.contentOrNull ?: url,
            type = type,
            title = response["title"]?.jsonPrimitive?.contentOrNull,
            description = description,
            apiKey = apiKey,
            oauth = oauth,
        )
    }

    suspend fun deleteServer(serverName: String) {
        client.delete {
            url { path("api/mcp/servers/$serverName") }
        }
    }

    private fun parseServerType(type: String?): McpServerType = when (type) {
        "sse" -> McpServerType.SSE
        "streamable-http", "http" -> McpServerType.STREAMABLE_HTTP
        "stdio" -> McpServerType.STDIO
        "websocket" -> McpServerType.WEBSOCKET
        else -> McpServerType.SSE
    }

    private fun parseApiKeyConfig(obj: JsonObject): McpApiKeyConfig = McpApiKeyConfig(
        source = when (obj["source"]?.jsonPrimitive?.contentOrNull) {
            "admin" -> McpApiKeySource.ADMIN
            else -> McpApiKeySource.USER
        },
        authorizationType = when (obj["authorization_type"]?.jsonPrimitive?.contentOrNull) {
            "basic" -> McpAuthorizationType.BASIC
            "custom" -> McpAuthorizationType.CUSTOM
            else -> McpAuthorizationType.BEARER
        },
        key = obj["key"]?.jsonPrimitive?.contentOrNull,
        customHeader = obj["custom_header"]?.jsonPrimitive?.contentOrNull,
    )

    private fun parseOAuthConfig(obj: JsonObject): McpOAuthConfig = McpOAuthConfig(
        authorizationUrl = obj["authorization_url"]?.jsonPrimitive?.contentOrNull,
        tokenUrl = obj["token_url"]?.jsonPrimitive?.contentOrNull,
        clientId = obj["client_id"]?.jsonPrimitive?.contentOrNull,
        clientSecret = obj["client_secret"]?.jsonPrimitive?.contentOrNull,
        scope = obj["scope"]?.jsonPrimitive?.contentOrNull,
    )
}

private val McpServerType.serialName: String
    get() = when (this) {
        McpServerType.SSE -> "sse"
        McpServerType.STREAMABLE_HTTP -> "streamable-http"
        McpServerType.HTTP -> "http"
        McpServerType.STDIO -> "stdio"
        McpServerType.WEBSOCKET -> "websocket"
    }

private val McpApiKeySource.serialName: String
    get() = when (this) {
        McpApiKeySource.ADMIN -> "admin"
        McpApiKeySource.USER -> "user"
    }

private val McpAuthorizationType.serialName: String
    get() = when (this) {
        McpAuthorizationType.BEARER -> "bearer"
        McpAuthorizationType.BASIC -> "basic"
        McpAuthorizationType.CUSTOM -> "custom"
    }
