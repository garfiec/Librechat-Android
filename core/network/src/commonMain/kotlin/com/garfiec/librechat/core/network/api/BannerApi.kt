package com.garfiec.librechat.core.network.api

import com.garfiec.librechat.core.model.Banner
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.path
import kotlinx.serialization.json.Json

class BannerApi constructor(
    private val client: HttpClient,
    private val json: Json,
) {

    /**
     * Fetches the active banner, or null when the server has none.
     *
     * The endpoint answers with a single banner object — never an array — and signals "no banner"
     * with an empty body: Express turns `res.send(null)` into a zero-length body *before* the
     * branch that would default the type to `text/html`, so the response carries no `Content-Type`
     * at all and ContentNegotiation never engages. Reading the raw body covers both shapes, and
     * matches [ConfigApi.getEndpoints], which needs the same treatment because this server can
     * serve JSON under a `text/html` type.
     *
     * Any other body still throws. An HTML page here means a proxy is answering for the API — only
     * a Cloudflare Access challenge is typed (`GatewayDetectionPlugin`), so a cookie-based gateway
     * or an SPA fallback reaches this decode. Swallowing that would make "the proxy is eating every
     * response" indistinguishable from "no banner is configured", with nothing in the log — the
     * same silent failure this fix exists to remove.
     */
    suspend fun getBanner(): Banner? {
        val text = client.get {
            url { path("api/banner") }
        }.bodyAsText().trim()
        if (text.isEmpty() || text == "null") return null
        return json.decodeFromString<Banner>(text)
    }
}
