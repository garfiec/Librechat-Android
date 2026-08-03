package com.garfiec.librechat.core.network.client

import com.garfiec.librechat.core.common.result.AccessGatewayException
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpClientPlugin
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.http.HttpHeaders
import io.ktor.util.AttributeKey

/**
 * Turns an access gateway's interception into a typed [AccessGatewayException] (issue #287).
 *
 * Without this the rejection is invisible until something downstream chokes on it, and *what*
 * chokes depends on the caller: a typed API call dies converting an HTML body, surfacing Ktor's
 * `NoTransformationFoundException` — which quotes the request URL, and so carries the gateway's
 * redirect, query string and `meta=` JWT into an error banner.
 *
 * Detection keys on the `WWW-Authenticate: Cloudflare-Access` header on the gateway's 302, which is
 * measured against a live Access deployment. Three reasons that header and not something looser:
 *
 * - **No body read.** This interceptor runs for every response including streaming ones; consuming
 *   a body here would break SSE.
 * - **A redirect alone is not enough.** The server legitimately 302s downloads off-authority to
 *   object storage, so "redirected somewhere else" would false-positive on file downloads.
 * - **Status is useless.** Following the redirect yields **200** `text/html`, so nothing
 *   status-based ever fires — which is why `HttpResponseValidator` cannot do this job.
 *
 * Install on **every** Ktor client — main, streaming and refresh. Each fails silently and
 * differently without it; `GatewayDetectionInstallTest` pins the set. The iOS SSE transport is not a
 * Ktor client and carries its own check against [AccessGatewaySignal].
 *
 * The exception deliberately carries **no server identity**: the live base URL is the wrong server
 * for a pinned multi-account refresh, and the SSE snapshot's URL is untrimmed. Add one back only
 * from the pinned URL, alongside the thing that displays it.
 */
class GatewayDetectionPlugin private constructor() {

    class Config

    companion object : HttpClientPlugin<Config, GatewayDetectionPlugin> {
        override val key = AttributeKey<GatewayDetectionPlugin>("GatewayDetection")

        override fun prepare(block: Config.() -> Unit): GatewayDetectionPlugin =
            GatewayDetectionPlugin()

        override fun install(plugin: GatewayDetectionPlugin, scope: HttpClient) {
            scope.plugin(HttpSend).intercept { request ->
                val call = execute(request)
                // Every line, not `headers[...]`: that returns only the first, and a gateway fronting
                // a server that already answers 401 with `Bearer` puts its challenge on a second line.
                val challenges = call.response.headers.getAll(HttpHeaders.WWWAuthenticate)
                if (challenges?.any(AccessGatewaySignal::isGatewayChallenge) == true) {
                    // Thrown from inside the redirect loop, so it surfaces instead of the sign-in
                    // page the redirect would otherwise deliver as a perfectly valid 200.
                    throw AccessGatewayException()
                }
                call
            }
        }
    }
}
