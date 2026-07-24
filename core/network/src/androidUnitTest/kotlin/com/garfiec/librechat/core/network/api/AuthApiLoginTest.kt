package com.garfiec.librechat.core.network.api

import com.garfiec.librechat.core.network.di.librechatJson
import com.google.common.truth.Truth.assertThat
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.url
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.serialization.ContentConvertException
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

/** Wire-shape tests for the login + 2FA endpoints, against payloads from the upstream controllers. */
class AuthApiLoginTest {

    private val json = librechatJson

    private fun api(engine: MockEngine): AuthApi = AuthApi(
        HttpClient(engine) {
            install(ContentNegotiation) { json(json) }
            defaultRequest {
                url("https://chat.example.com")
                contentType(ContentType.Application.Json)
            }
        },
    )

    private fun jsonHeaders() = headersOf(HttpHeaders.ContentType, "application/json")

    @Test
    fun `login parses the twoFAPending challenge`() = runTest {
        val engine = MockEngine {
            respond(
                content = """{"twoFAPending":true,"tempToken":"temp-abc"}""",
                status = HttpStatusCode.OK,
                headers = jsonHeaders(),
            )
        }

        val result = api(engine).login("a@b.com", "pw")

        assertThat(result.response.twoFAPending).isTrue()
        assertThat(result.response.tempToken).isEqualTo("temp-abc")
        assertThat(result.response.token).isNull()
        assertThat(result.response.user).isNull()
    }

    @Test
    fun `login parses a normal success with the refresh cookie`() = runTest {
        val engine = MockEngine {
            respond(
                content = """{"token":"jwt-1","user":{"_id":"u1","email":"a@b.com","name":"A","role":"USER"}}""",
                status = HttpStatusCode.OK,
                headers = headersOf(
                    HttpHeaders.ContentType to listOf("application/json"),
                    HttpHeaders.SetCookie to listOf("refreshToken=rt-1; Path=/; HttpOnly; SameSite=Lax"),
                ),
            )
        }

        val result = api(engine).login("a@b.com", "pw")

        assertThat(result.response.twoFAPending).isFalse()
        assertThat(result.response.token).isEqualTo("jwt-1")
        assertThat(result.response.user?.email).isEqualTo("a@b.com")
        assertThat(result.response.user?.mongoId).isEqualTo("u1")
        assertThat(result.refreshToken).isEqualTo("rt-1")
    }

    @Test
    fun `verifyTempToken sends a TOTP code as token with no backupCode`() = runTest {
        var body: String? = null
        val engine = MockEngine { request ->
            body = String(request.body.toByteArray())
            respond(
                content = """{"token":"jwt-1","user":{"_id":"u1","email":"a@b.com"}}""",
                status = HttpStatusCode.OK,
                headers = jsonHeaders(),
            )
        }

        api(engine).verifyTempToken(tempToken = "temp-abc", totpCode = "123456")

        val sent = json.parseToJsonElement(body!!).jsonObject
        assertThat(sent["tempToken"]?.jsonPrimitive?.content).isEqualTo("temp-abc")
        assertThat(sent["token"]?.jsonPrimitive?.content).isEqualTo("123456")
        assertThat(sent).doesNotContainKey("backupCode")
    }

    @Test
    fun `verifyTempToken sends a backup code as backupCode and omits token`() = runTest {
        var body: String? = null
        val engine = MockEngine { request ->
            body = String(request.body.toByteArray())
            respond(
                content = """{"token":"jwt-1","user":{"_id":"u1","email":"a@b.com"}}""",
                status = HttpStatusCode.OK,
                headers = jsonHeaders(),
            )
        }

        api(engine).verifyTempToken(tempToken = "temp-abc", backupCode = "abcd1234")

        val sent = json.parseToJsonElement(body!!).jsonObject
        assertThat(sent["tempToken"]?.jsonPrimitive?.content).isEqualTo("temp-abc")
        assertThat(sent["backupCode"]?.jsonPrimitive?.content).isEqualTo("abcd1234")
        assertThat(sent).doesNotContainKey("token")
    }

    @Test
    fun `verifyTempToken wraps an undecodable 2xx body as ContentConvertException`() = runTest {
        // Pins the exact type the repository's classifier keys on: Ktor's ContentNegotiation wraps
        // every 2xx body decode failure in JsonConvertException (a ContentConvertException), NOT a
        // bare kotlinx SerializationException. A malformed success body must surface as this type so
        // the repository can report "code consumed, session incomplete" instead of a connectivity lie.
        val engine = MockEngine {
            respond(
                content = """{"token":"jwt-1","user":{"_id":123}""", // truncated + wrong-typed: undecodable
                status = HttpStatusCode.OK,
                headers = jsonHeaders(),
            )
        }

        val error = runCatching {
            api(engine).verifyTempToken(tempToken = "temp-abc", totpCode = "123456")
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(ContentConvertException::class.java)
    }
}
