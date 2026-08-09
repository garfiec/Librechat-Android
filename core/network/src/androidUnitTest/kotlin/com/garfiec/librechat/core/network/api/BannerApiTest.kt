package com.garfiec.librechat.core.network.api

import com.garfiec.librechat.core.model.Banner
import com.garfiec.librechat.core.network.di.librechatJson
import com.google.common.truth.Truth.assertThat
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Pins the response shapes of `GET /api/banner`. The route sends a single banner object, or an
 * empty body when none is configured — never an array — so decoding it as a list failed on every
 * server in both states. These cases are the wire captures that regression has to stay fixed
 * against.
 *
 * ContentNegotiation is installed as it is in production to show it cannot rescue any of this:
 * [BannerApi.getBanner] reads the raw body, so the shipped [librechatJson] below is what decodes.
 */
class BannerApiTest {

    private suspend fun getBanner(body: String, contentType: String? = "application/json"): Banner? {
        val engine = MockEngine {
            respond(
                content = body,
                status = HttpStatusCode.OK,
                headers = contentType?.let { headersOf(HttpHeaders.ContentType, it) } ?: headersOf(),
            )
        }
        val client = HttpClient(engine) { install(ContentNegotiation) { json(librechatJson) } }
        return BannerApi(client, librechatJson).getBanner()
    }

    @Test
    fun `no banner configured returns null for an empty body with no content type`() = runTest {
        // The live capture: Express turns res.send(null) into a zero-length body and never sets
        // Content-Type, so ContentNegotiation cannot engage at all.
        assertThat(getBanner(body = "", contentType = null)).isNull()
    }

    @Test
    fun `banner object decodes with mongo-only fields ignored`() = runTest {
        val banner = getBanner(
            """
            {
              "_id": "68a1f0c0c0ffee0000000001",
              "__v": 0,
              "bannerId": "maintenance-2026-08",
              "message": "Scheduled maintenance on Sunday.",
              "displayFrom": "2026-08-01T00:00:00.000Z",
              "displayTo": null,
              "type": "banner",
              "isPublic": true,
              "persistable": false,
              "tenantId": "acme",
              "createdAt": "2026-08-01T00:00:00.000Z",
              "updatedAt": "2026-08-01T00:00:00.000Z"
            }
            """.trimIndent(),
        )

        assertThat(banner).isNotNull()
        assertThat(banner?.bannerId).isEqualTo("maintenance-2026-08")
        assertThat(banner?.message).isEqualTo("Scheduled maintenance on Sunday.")
        assertThat(banner?.displayFrom).isEqualTo("2026-08-01T00:00:00.000Z")
        assertThat(banner?.displayTo).isNull()
        assertThat(banner?.persistable).isFalse()
    }

    @Test
    fun `literal null body returns null`() = runTest {
        assertThat(getBanner("null")).isNull()
    }

    @Test(expected = Exception::class)
    fun `html error page surfaces as an error rather than as no banner`() = runTest {
        // A proxy answering for the API must not read as "no banner is configured" — that is the
        // silent failure this fix removes, and it would leave nothing in the log to find.
        getBanner("<!doctype html><html><body>Not found</body></html>", contentType = "text/html")
    }

    @Test(expected = Exception::class)
    fun `malformed json still throws so it surfaces as an error`() = runTest {
        // Only the documented "no banner" shapes are swallowed — a broken payload has to stay
        // visible, or protocol drift decodes to a silently missing banner.
        getBanner("""{"bannerId": }""")
    }
}
