package com.garfiec.librechat.core.network.api

import com.google.common.truth.Truth.assertThat
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.readRemaining
import io.ktor.utils.io.writer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json
import org.junit.Test

/**
 * Exercises the multipart encoding of the skill upload + import endpoints — the
 * one untested area of the file feature, and exactly where wire bugs hide
 * (part names, filename quoting, Content-Type, the relativePath form field).
 * Reads the actual outgoing multipart body via a MockEngine.
 */
class SkillsApiMultipartTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    private fun client(engine: MockEngine): HttpClient = HttpClient(engine) {
        install(ContentNegotiation) { json(json) }
    }

    /** Renders a request's MultiPartFormDataContent body to bytes for assertions.
     *  A `writer` coroutine drives WriteChannelContent.writeTo into a channel we
     *  then drain — avoids a rendezvous deadlock between fill and read. */
    private suspend fun renderBody(content: OutgoingContent): String = coroutineScope {
        val writable = content as OutgoingContent.WriteChannelContent
        val channel = writer(Dispatchers.Default) { writable.writeTo(channel) }.channel
        channel.readRemaining().readByteArray().decodeToString()
    }

    private val skillJson = """{"_id":"sk-1","name":"n","description":"d","version":1,"fileCount":0}"""

    @Test
    fun `uploadSkillFile posts multipart with file part and relativePath form field`() = runTest {
        var method: HttpMethod? = null
        var path: String? = null
        var body: String? = null
        val engine = MockEngine { request ->
            method = request.method
            path = request.url.encodedPath
            body = renderBody(request.body)
            respond(
                content = """{"_id":"f1","relativePath":"notes.md","bytes":3}""",
                status = HttpStatusCode.Created,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }

        val result = SkillsApi(client(engine)).uploadSkillFile(
            skillId = "sk-1",
            relativePath = "notes.md",
            bytes = "abc".encodeToByteArray(),
            filename = "notes.md",
            mimeType = "text/markdown",
        )

        assertThat(method).isEqualTo(HttpMethod.Post)
        assertThat(path).isEqualTo("/api/skills/sk-1/files")
        // The file part carries the filename + declared content type.
        assertThat(body).contains("filename=\"notes.md\"")
        assertThat(body).contains("text/markdown")
        // The relativePath form field is present with its value. Ktor may render the
        // Content-Disposition param name with or without RFC-7578 quotes across versions.
        assertThat(body).containsMatch("name=\"?relativePath\"?")
        assertThat(body).contains("notes.md")
        assertThat(result.relativePath).isEqualTo("notes.md")
    }

    @Test
    fun `importSkill posts multipart file to the import endpoint`() = runTest {
        var method: HttpMethod? = null
        var path: String? = null
        var body: String? = null
        val engine = MockEngine { request ->
            method = request.method
            path = request.url.encodedPath
            body = renderBody(request.body)
            respond(
                content = skillJson,
                status = HttpStatusCode.Created,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }

        val result = SkillsApi(client(engine)).importSkill(
            bytes = "PK".encodeToByteArray(),
            filename = "pack.zip",
            mimeType = "application/zip",
        )

        assertThat(method).isEqualTo(HttpMethod.Post)
        assertThat(path).isEqualTo("/api/skills/import")
        assertThat(body).contains("filename=\"pack.zip\"")
        assertThat(body).contains("application/zip")
        assertThat(result.id).isEqualTo("sk-1")
    }

    @Test
    fun `getSkillFileContent encodes a nested relativePath into the path`() = runTest {
        var path: String? = null
        val engine = MockEngine { request ->
            path = request.url.encodedPath
            respond(
                content = """{"relativePath":"dir/a.txt","mimeType":"text/plain","isBinary":false,"bytes":1}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }

        SkillsApi(client(engine)).getSkillFileContent("sk-1", "dir/a.txt")
        // The relativePath is a single encoded path part — the slash is escaped.
        assertThat(path).contains("/api/skills/sk-1/files/")
        assertThat(path).contains("dir%2Fa.txt")
    }
}
