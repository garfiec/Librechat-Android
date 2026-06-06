package com.garfiec.librechat.core.network.client

import co.touchlab.kermit.Logger
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpClientPlugin
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.client.request.HttpRequestPipeline
import io.ktor.client.request.headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.util.AttributeKey

class AuthInterceptorPlugin private constructor(
    private val tokenManager: TokenManager,
    private val serverUrlProvider: ServerUrlProvider?,
) {
    class Config {
        lateinit var tokenManager: TokenManager

        /**
         * Resolves the configured LibreChat server base URL. When set, the
         * Authorization header is attached ONLY to requests whose host matches
         * the base URL host — so a presigned absolute URL fetch to a third-party
         * CDN (S3/CloudFront, e.g. file download-url) never leaks the session
         * bearer token to that host. Null disables host-scoping (attach to every
         * non-auth-path request), preserving legacy behavior for callers/tests
         * that don't wire a provider.
         */
        var serverUrlProvider: ServerUrlProvider? = null
    }

    /**
     * True when [requestHost] belongs to the configured server (so the bearer
     * token is safe to attach). Returns true when host-scoping is disabled
     * (no [serverUrlProvider]) or the base URL isn't resolved yet (empty host) —
     * the latter only happens during cold-start warm-up, before any cross-host
     * CDN fetch can occur, so it preserves same-origin auth without leaking.
     */
    private fun isSameHostAsServer(requestHost: String): Boolean {
        val provider = serverUrlProvider ?: return true
        val baseUrl = provider.getBaseUrl()
        if (baseUrl.isEmpty()) return true
        val baseHost = runCatching { Url(baseUrl).host }.getOrNull()
        if (baseHost.isNullOrEmpty()) return true
        return requestHost.equals(baseHost, ignoreCase = true)
    }

    companion object : HttpClientPlugin<Config, AuthInterceptorPlugin> {
        override val key = AttributeKey<AuthInterceptorPlugin>("AuthInterceptor")
        private val RetryFlag = AttributeKey<Boolean>("AuthRetried")

        override fun prepare(block: Config.() -> Unit): AuthInterceptorPlugin {
            val config = Config().apply(block)
            return AuthInterceptorPlugin(config.tokenManager, config.serverUrlProvider)
        }

        override fun install(plugin: AuthInterceptorPlugin, scope: HttpClient) {
            val skipPaths = setOf(
                "auth/login", "auth/register", "auth/refresh",
                "auth/requestPasswordReset", "auth/resetPassword",
            )

            // Attach token to outgoing requests. Runs at State, which is after
            // defaultRequest (Before) has applied the base URL — so for the
            // common relative-path call `context.url.host` is already the base
            // host, and for an absolute cross-host URL (e.g. a presigned CDN
            // download) it is that foreign host.
            scope.requestPipeline.intercept(HttpRequestPipeline.State) {
                val path = context.url.buildString()
                val isSkipPath = skipPaths.any { path.contains(it) }
                if (!isSkipPath && plugin.isSameHostAsServer(context.url.host)) {
                    val token = plugin.tokenManager.getAccessToken()
                    if (token != null) {
                        context.headers.append(HttpHeaders.Authorization, "Bearer $token")
                    }
                }
            }

            // Intercept 401 responses at the HttpSend level for proper retry.
            scope.plugin(HttpSend).intercept { request ->
                val originalCall = execute(request)

                if (originalCall.response.status != HttpStatusCode.Unauthorized) {
                    return@intercept originalCall
                }

                // Host-scope the refresh-and-reattach exactly like the build-phase
                // attach: never refresh a token and re-send it to a foreign host
                // (e.g. a presigned CDN URL). For a non-base host, pass the
                // original 401 straight through with no token on the retry.
                if (!plugin.isSameHostAsServer(request.url.host)) {
                    return@intercept originalCall
                }

                val alreadyRetried = request.attributes.getOrNull(RetryFlag) == true
                if (alreadyRetried) {
                    Logger.w("Auth") { "401 after retry - session expired" }
                    plugin.tokenManager.emitSessionExpired()
                    return@intercept originalCall
                }

                Logger.d("Auth") { "401 received, attempting token refresh" }
                val refreshed = plugin.tokenManager.refreshAccessToken()
                if (!refreshed) {
                    Logger.w("Auth") { "Token refresh failed - session expired" }
                    plugin.tokenManager.emitSessionExpired()
                    return@intercept originalCall
                }

                val newToken = plugin.tokenManager.getAccessToken()
                request.headers {
                    remove(HttpHeaders.Authorization)
                    if (newToken != null) {
                        append(HttpHeaders.Authorization, "Bearer $newToken")
                    }
                }
                request.attributes.put(RetryFlag, true)

                execute(request)
            }
        }
    }
}
