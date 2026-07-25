package com.garfiec.librechat.core.network.api

import com.garfiec.librechat.core.model.ToolFavoriteItemType
import com.google.common.truth.Truth.assertThat
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.accept
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Test

/**
 * Pins the encoding of the per-item favorite path. Express decodes a route param exactly once, so
 * an id that is escaped twice is stored and looked up under the mangled spelling — the star then
 * reads unpinned forever and the DELETE can never find the row. Only the raw path the server sees
 * proves the encoding, which is why these assert on the built URL rather than on the helper.
 */
class FavoritesApiToolPathTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    private fun clientCapturing(capture: (String) -> Unit): HttpClient {
        val engine = MockEngine { request ->
            capture(request.url.encodedPath)
            respond(
                content = "",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        return HttpClient(engine) {
            install(ContentNegotiation) { json(json) }
            defaultRequest {
                contentType(ContentType.Application.Json)
                accept(ContentType.Application.Json)
            }
        }
    }

    @Test
    fun `an id with a space and a slash is escaped exactly once`() = runTest {
        var path: String? = null
        FavoritesApi(clientCapturing { path = it })
            .addToolFavorite(ToolFavoriteItemType.MCP, "my server/x")

        assertThat(path).isEqualTo("/api/user/settings/favorites/tools/mcp/my%20server%2Fx")
    }

    @Test
    fun `a plain id is left alone`() = runTest {
        var path: String? = null
        FavoritesApi(clientCapturing { path = it })
            .addToolFavorite(ToolFavoriteItemType.TOOL, "web_search")

        assertThat(path).isEqualTo("/api/user/settings/favorites/tools/tool/web_search")
    }

    @Test
    fun `remove addresses the same path as add`() = runTest {
        var addPath: String? = null
        var removePath: String? = null
        var method: HttpMethod? = null

        FavoritesApi(clientCapturing { addPath = it })
            .addToolFavorite(ToolFavoriteItemType.SKILL, "data ops/v2")

        val engine = MockEngine { request ->
            removePath = request.url.encodedPath
            method = request.method
            respond("", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        FavoritesApi(HttpClient(engine) { install(ContentNegotiation) { json(json) } })
            .removeToolFavorite(ToolFavoriteItemType.SKILL, "data ops/v2")

        assertThat(removePath).isEqualTo(addPath)
        assertThat(method).isEqualTo(HttpMethod.Delete)
    }
}
