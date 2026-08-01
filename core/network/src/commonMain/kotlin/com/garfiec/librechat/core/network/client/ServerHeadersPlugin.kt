package com.garfiec.librechat.core.network.client

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpClientPlugin
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.client.request.HttpRequestPipeline
import io.ktor.util.AttributeKey

/**
 * Records exactly which custom headers this plugin put on a request, so the redirect guard removes
 * precisely those and nothing else (notably: not the app's own `Cookie`, which a user cookie may have
 * been merged into).
 */
internal val AppliedCustomHeadersKey = AttributeKey<Map<String, String>>("AppliedCustomHeaders")

/**
 * The server base URL a request is pinned to, for clients that have no [SwitchGate] snapshot. Set by
 * the URL-pinned token refresh so its headers key off the server the refresh token actually belongs
 * to, rather than whichever server happens to be live when the interceptor runs.
 */
val PinnedServerBaseUrlKey = AttributeKey<String>("PinnedServerBaseUrl")

/**
 * Attaches the user's per-server gateway headers (issue #287) and keeps them from escaping the
 * server's authority.
 *
 * Two interceptors, because one phase cannot do both jobs:
 *
 * 1. **`HttpRequestPipeline.State`** — attach. Runs after the base URL has been applied, so
 *    `context.url` is the real target: a relative API call carries the server's authority, an absolute
 *    presigned CDN fetch carries the CDN's.
 * 2. **`HttpSend`** — strip on cross-authority redirect. Ktor's `HttpRedirect` copies every header to
 *    the redirect target and strips only `Authorization`, and it re-executes at the `HttpSend` level
 *    without re-running the request pipeline — so the phase-1 gate cannot see the new host at all. A
 *    server-supplied absolute URL (`FilesApi.downloadFromUrl`) that 302s off-domain would otherwise
 *    hand a long-lived, non-rotating gateway secret to an arbitrary host. `HttpRedirect` is installed
 *    before user plugins and the `HttpSend` chain runs first-registered-outermost, so this interceptor
 *    executes *inside* the redirect loop and sees every hop.
 */
class ServerHeadersPlugin private constructor(
    private val serverHeadersProvider: ServerHeadersProvider,
    private val serverUrlProvider: ServerUrlProvider?,
) {
    class Config {
        lateinit var serverHeadersProvider: ServerHeadersProvider

        /**
         * Fallback source of the server base URL for requests with no [RequestIdentity] snapshot and
         * no [PinnedServerBaseUrlKey]. Null leaves those requests headerless — fail-closed, since a
         * request whose server can't be established must not carry a gateway credential.
         */
        var serverUrlProvider: ServerUrlProvider? = null
    }

    companion object : HttpClientPlugin<Config, ServerHeadersPlugin> {
        override val key = AttributeKey<ServerHeadersPlugin>("ServerHeaders")

        override fun prepare(block: Config.() -> Unit): ServerHeadersPlugin {
            val config = Config().apply(block)
            return ServerHeadersPlugin(config.serverHeadersProvider, config.serverUrlProvider)
        }

        override fun install(plugin: ServerHeadersPlugin, scope: HttpClient) {
            scope.requestPipeline.intercept(HttpRequestPipeline.State) {
                val snapshot = context.attributes.getOrNull(RequestIdentityKey)
                val baseUrl = plugin.resolveBaseUrl(snapshot, context.attributes.getOrNull(PinnedServerBaseUrlKey))
                val custom = if (snapshot != null) {
                    // The barrier already awaited the warm-up and read the map under its own lock;
                    // re-reading here could pair a *newer* header set with an older snapshot URL.
                    snapshot.customHeaders
                } else {
                    plugin.serverHeadersProvider.awaitWarm()
                    plugin.serverHeadersProvider.headersFor(baseUrl.orEmpty())
                }
                if (custom.isNotEmpty() && isSameServerAuthority(context.url, baseUrl)) {
                    context.headers.applyCustomHeaders(custom)
                    context.attributes.put(AppliedCustomHeadersKey, custom)
                }
            }

            scope.plugin(HttpSend).intercept { request ->
                // What this request's server *would* carry, independent of what is on it right now.
                // A redirect rebuilds the request; if Ktor ever stops carrying attributes across that
                // rebuild, fall back to the snapshot and then to the live store rather than silently
                // losing the strip.
                val custom = request.attributes.getOrNull(AppliedCustomHeadersKey)
                    ?: request.attributes.getOrNull(RequestIdentityKey)?.customHeaders
                    ?: plugin.serverHeadersProvider.headersFor(plugin.serverUrlProvider?.getBaseUrl().orEmpty())
                if (custom.isNotEmpty()) {
                    val baseUrl = plugin.resolveBaseUrl(
                        request.attributes.getOrNull(RequestIdentityKey),
                        request.attributes.getOrNull(PinnedServerBaseUrlKey),
                    )
                    // Both directions, because a redirect chain can leave the server's authority and
                    // come back (origin → object store → origin is an ordinary signed-URL shape, and
                    // an Access edge bounces through its own domain by design). The `State` attach
                    // phase runs once per *call*, not per hop, so a strip that is never undone leaves
                    // the final hop hitting the gateway with no credential — which comes back as a
                    // 200 HTML login page and reads as a corrupt download, not as an auth error.
                    val applied = request.attributes.contains(AppliedCustomHeadersKey)
                    when {
                        !isSameServerAuthority(request.url, baseUrl) && applied -> {
                            request.headers.stripCustomHeaders(custom)
                            request.attributes.remove(AppliedCustomHeadersKey)
                        }
                        isSameServerAuthority(request.url, baseUrl) && !applied -> {
                            request.headers.applyCustomHeaders(custom)
                            request.attributes.put(AppliedCustomHeadersKey, custom)
                        }
                    }
                }
                execute(request)
            }
        }
    }

    /**
     * Which server a request belongs to, most-specific first: the switch-barrier snapshot, then an
     * explicitly pinned URL, then the live provider. Never a mix — pairing one source's URL with
     * another's headers is the tear this whole ordering exists to prevent.
     */
    private fun resolveBaseUrl(snapshot: RequestIdentity?, pinned: String?): String? =
        snapshot?.baseUrl?.takeIf { it.isNotEmpty() }
            ?: pinned?.takeIf { it.isNotEmpty() }
            ?: serverUrlProvider?.getBaseUrl()
}
