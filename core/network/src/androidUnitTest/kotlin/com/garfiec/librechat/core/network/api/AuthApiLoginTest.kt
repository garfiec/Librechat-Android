package com.garfiec.librechat.core.network.api

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
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

/**
 * Wire-shape tests for the login + 2FA endpoints, against payloads copied from the upstream
 * controllers (`api/server/controllers/auth/LoginController.js`,
 * `api/server/controllers/auth/TwoFactorAuthController.js`).
 *
 * These exist because issue #277 was a pure naming mismatch — the client modelled the 2FA flag as
 * `twoFactorRequired`, a name that never existed upstream, and `ignoreUnknownKeys` dropped it
 * silently. Nothing in the suite deserialized a real backend body, so no test could see it.
 */
class AuthApiLoginTest {

    /** Mirrors the client Json in `NetworkModule.kt` — `explicitNulls = false` matters here. */
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = false
        explicitNulls = false
        coerceInputValues = true
    }

    private fun api(engine: MockEngine): AuthApi = AuthApi(
        HttpClient(engine) {
            install(ContentNegotiation) { json(json) }
            // AuthApi builds relative paths and sets no Content-Type; the real graph supplies both
            // via defaultRequest (LibreChatHttpClient.kt).
            defaultRequest {
                url("https://chat.example.com")
                contentType(ContentType.Application.Json)
            }
        },
    )

    private fun jsonHeaders() = headersOf(HttpHeaders.ContentType, "application/json")

    @Test
    fun `login parses the twoFAPending challenge`() = runTest {
        // Verbatim upstream: LoginController returns 200 with no token and no user.
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
        // The backend branches `if (token) verifyTOTP else if (backupCode) verifyBackupCode`, so a
        // backup code that travels in `token` is TOTP-verified and can only ever 401.
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
}
