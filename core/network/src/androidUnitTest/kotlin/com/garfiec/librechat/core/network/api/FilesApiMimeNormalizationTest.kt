package com.garfiec.librechat.core.network.api

import com.google.common.truth.Truth.assertThat
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
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
 * The `.sh` upload defect lives on the wire, not in the router.
 *
 * `UploadRoutingTest` pins `uploadMimeType` as a function, which says nothing about what the
 * multipart part carries. A part left with whatever Android's `DocumentsProvider` reported —
 * `text/x-sh`, which upstream lists nowhere — is answered 415 `Unsupported file type: text/x-sh`,
 * so only reading the outgoing body proves the rewrite reaches the header the server reads.
 */
class FilesApiMimeNormalizationTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    private fun client(engine: MockEngine): HttpClient = HttpClient(engine) {
        install(ContentNegotiation) { json(json) }
    }

    /** See `SkillsApiMultipartTest.renderBody` — a writer coroutine avoids a rendezvous deadlock. */
    private suspend fun renderBody(content: OutgoingContent): String = coroutineScope {
        val writable = content as OutgoingContent.WriteChannelContent
        val channel = writer(Dispatchers.Default) { writable.writeTo(channel) }.channel
        channel.readRemaining().readByteArray().decodeToString()
    }

    private suspend fun uploadWithType(type: String): String {
        var body: String? = null
        val engine = MockEngine { request ->
            body = renderBody(request.body)
            respond(
                content = """{"file_id":"f1","filename":"deploy.sh","type":"application/x-sh",""" +
                    """"filepath":"/uploads/deploy.sh","bytes":10}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        FilesApi(client(engine)).uploadFile(
            bytes = "#!/bin/sh\n".encodeToByteArray(),
            filename = "deploy.sh",
            type = type,
        )
        return body!!
    }

    @Test
    fun `the android shell-script spelling is rewritten before it reaches the wire`() = runTest {
        val body = uploadWithType("text/x-sh")

        assertThat(body).contains("application/x-sh")
        // The negative half is the one that matters: the raw spelling is what the gate 415s on,
        // and a rewrite that merely ADDED the canonical name somewhere would still fail.
        assertThat(body).doesNotContain("text/x-sh")
    }

    @Test
    fun `an upstream alias is sent exactly as the platform reported it`() = runTest {
        // The server normalises its own alias table on receipt and each entry already clears the
        // upload gate. Rewriting those here would change what the server records for no reason —
        // and would quietly turn a mirrored table into a client-side behaviour.
        val body = uploadWithType("application/x-zip-compressed")

        assertThat(body).contains("application/x-zip-compressed")
        assertThat(body).doesNotContain("application/zip\r")
    }

    @Test
    fun `an ordinary type passes through untouched`() = runTest {
        val body = uploadWithType("text/markdown")

        assertThat(body).contains("text/markdown")
    }
}
