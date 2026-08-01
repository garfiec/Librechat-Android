package com.garfiec.librechat.core.network.client

import com.garfiec.librechat.core.common.result.AccessGatewayException
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpClientPlugin
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.http.HttpHeaders
import io.ktor.util.AttributeKey

/** The `WWW-Authenticate` scheme Cloudflare Access answers a rejected request with. */
private const val CLOUDFLARE_ACCESS_SCHEME = "Cloudflare-Access"

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
 * Installed on the main client only, so it covers config, auth, user and the chat POST. The SSE and
 * refresh clients are not covered and fail differently: a hung stream, and a silently unrecoverable
 * session.
 *
 * A gateway that is not Cloudflare Access (Authelia, oauth2-proxy) does not set this header; those
 * still degrade to the generic "unexpected response from the server" rather than leaking anything.
 */
class GatewayDetectionPlugin private constructor(
    private val serverUrlProvider: ServerUrlProvider?,
) {
    class Config {
        var serverUrlProvider: ServerUrlProvider? = null
    }

    companion object : HttpClientPlugin<Config, GatewayDetectionPlugin> {
        override val key = AttributeKey<GatewayDetectionPlugin>("GatewayDetection")

        override fun prepare(block: Config.() -> Unit): GatewayDetectionPlugin =
            GatewayDetectionPlugin(Config().apply(block).serverUrlProvider)

        override fun install(plugin: GatewayDetectionPlugin, scope: HttpClient) {
            scope.plugin(HttpSend).intercept { request ->
                val call = execute(request)
                val wwwAuthenticate = call.response.headers[HttpHeaders.WWWAuthenticate].orEmpty()
                if (wwwAuthenticate.contains(CLOUDFLARE_ACCESS_SCHEME, ignoreCase = true)) {
                    // Thrown from inside the redirect loop, so it surfaces instead of the sign-in
                    // page the redirect would otherwise deliver as a perfectly valid 200.
                    throw AccessGatewayException(serverUrl = plugin.serverUrlProvider?.getBaseUrl())
                }
                call
            }
        }
    }
}
