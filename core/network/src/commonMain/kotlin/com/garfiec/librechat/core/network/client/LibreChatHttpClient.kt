package com.garfiec.librechat.core.network.client

import co.touchlab.kermit.Logger
import com.garfiec.librechat.core.logging.Diag
import com.garfiec.librechat.core.logging.LogOrigin
import com.garfiec.librechat.core.logging.redact.LogRedactor
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.plugins.DefaultRequest.DefaultRequestBuilder
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpRequestRetryConfig
import io.ktor.client.plugins.HttpResponseValidator
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.http.takeFrom
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import io.ktor.client.plugins.logging.Logger as KtorLogger

object LibreChatHttpClient {

    fun create(
        engineFactory: HttpClientEngineFactory<*>,
        json: Json,
        tokenManager: TokenManager,
        serverUrlProvider: ServerUrlProvider,
        redactor: LogRedactor,
        accountReadyGate: AccountReadyGate? = null,
        switchGate: SwitchGate? = null,
        debug: Boolean = false,
    ): HttpClient = HttpClient(engineFactory) {
        install(ContentNegotiation) {
            json(json)
        }

        install(Logging) {
            logger = object : KtorLogger {
                override fun log(message: String) {
                    // Route through the shared redactor so Logcat/NSLog get the same scrubbing as the
                    // persistent sink (tokens, JWTs, emails, hosts, IDs) — one redaction policy, not two.
                    Logger.d("HTTP") { redactor.redact(message) }
                }
            }
            level = if (debug) LogLevel.HEADERS else LogLevel.NONE
        }

        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 120_000
        }

        install(HttpRequestRetry) {
            configureRetryPolicy()
        }

        install(AuthInterceptorPlugin) {
            this.tokenManager = tokenManager
            // Host-scope the bearer token so a presigned absolute-URL fetch to a
            // third-party CDN (S3/CloudFront file download-url) never leaks the
            // LibreChat session token to that host.
            this.serverUrlProvider = serverUrlProvider
        }

        // The SwitchBarrierPlugin (when a SwitchGate is wired) captures a consistent
        // (url, bearer, account) snapshot per request and resolves the URL against it, subsuming
        // ServerUrlReadyPlugin's cold-start await. Without a gate (tests) fall back to the plain
        // readiness plugin so a cold-start request can't be built against an empty base URL.
        if (switchGate != null) {
            install(SwitchBarrierPlugin) {
                this.switchGate = switchGate
            }
        } else {
            install(ServerUrlReadyPlugin) {
                this.serverUrlProvider = serverUrlProvider
                this.accountReadyGate = accountReadyGate
            }
        }

        HttpResponseValidator {
            validateResponse { response ->
                if (!response.status.isSuccess()) {
                    val statusCode = response.status.value
                    val bodyText = try { response.bodyAsText() } catch (_: Exception) { "" }
                    val errorMessage = extractErrorMessage(json, bodyText, statusCode)
                    val isBanned = statusCode == 403 && bodyText.contains("ban", ignoreCase = true)

                    Diag.w(
                        "HTTP",
                        origin = LogOrigin.SERVER,
                        attrs = mapOf(
                            "status" to statusCode.toString(),
                            "path" to response.call.request.url.encodedPath,
                            "method" to response.call.request.method.value,
                        ),
                    ) { "HTTP $statusCode" }

                    if (isBanned) {
                        // Scoped to the request's snapshot account: a ban landing on a switched-away
                        // account's straggler request must not tear down the live session. A pending
                        // add-flow request passes through entirely (its accountId is null, which
                        // emitSessionExpired treats as "active session, always emit") — a ban from
                        // the server being ADDED belongs to the add flow, mirroring the 401 path.
                        val identity = response.call.request.attributes.getOrNull(RequestIdentityKey)
                        if (identity?.isPending != true) {
                            tokenManager.emitSessionExpired(identity?.accountId)
                        }
                    }

                    throw ApiException(
                        statusCode = statusCode,
                        message = errorMessage,
                        isBanned = isBanned,
                        body = bodyText.ifBlank { null },
                    )
                }
            }
        }

        defaultRequest {
            applyBrowserDefaults(serverUrlProvider)
            contentType(ContentType.Application.Json)
        }
    }

    // Standard Chrome on Android UA string — ua-parser-js will detect "Chrome" as browser name
    const val BROWSER_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    private fun extractErrorMessage(json: Json, body: String, statusCode: Int): String {
        if (body.isNotBlank()) {
            try {
                val jsonObj = json.parseToJsonElement(body).jsonObject
                val msg = (jsonObj["message"] as? JsonPrimitive)?.content
                    ?: (jsonObj["error"] as? JsonPrimitive)?.content
                    ?: (jsonObj["error_message"] as? JsonPrimitive)?.content
                if (!msg.isNullOrBlank()) return msg
            } catch (_: Exception) {
                if (body.contains("banned", ignoreCase = true) || body.contains("forbidden", ignoreCase = true)) {
                    return "Access denied. Your account may have been restricted."
                }
            }
        }

        return when (statusCode) {
            400 -> "Bad request"
            403 -> "Access denied"
            404 -> "Not found"
            429 -> "Too many requests. Please try again later."
            in 500..599 -> "Server error. Please try again later."
            else -> "Request failed (HTTP $statusCode)"
        }
    }
}

/**
 * Retry policy shared by production and the guard test so they can't drift. Replays only
 * side-effect-free methods: a retried POST/PATCH would mint a duplicate chat job + orphaned
 * generation or a duplicate upload, and a retried DELETE that already succeeded server-side would
 * surface a spurious 404. The allow-list means an unknown or newly added method defaults to *not*
 * retried.
 */
internal fun HttpRequestRetryConfig.configureRetryPolicy() {
    // A 5xx can arrive as a response (retryIf) or, once HttpResponseValidator throws, as an
    // ApiException (retryOnExceptionIf) — so both predicates carry the same method gate.
    retryIf(maxRetries = 2) { request, response ->
        request.method.isRetrySafe() && response.status.value in 500..599
    }
    retryOnExceptionIf(maxRetries = 2) { request, cause ->
        request.method.isRetrySafe() && cause !is kotlinx.coroutines.CancellationException
    }
    exponentialDelay()
}

private val RETRY_SAFE_METHODS = setOf(HttpMethod.Get, HttpMethod.Head, HttpMethod.Options)

private fun HttpMethod.isRetrySafe(): Boolean = this in RETRY_SAFE_METHODS

/**
 * Applies the base URL and the browser [User-Agent][LibreChatHttpClient.BROWSER_USER_AGENT] that
 * the stock LibreChat server's `ua-parser-js` middleware requires — a single audited definition
 * point shared by the main and streaming clients so the browser-UA invariant lives in one place.
 * The refresh client deliberately omits this (its `/auth/refresh` route is UA-ungated).
 */
internal fun DefaultRequestBuilder.applyBrowserDefaults(serverUrlProvider: ServerUrlProvider) {
    val baseUrl = serverUrlProvider.getBaseUrl()
    if (baseUrl.isNotEmpty()) {
        url.takeFrom(baseUrl)
    }
    headers.append(HttpHeaders.UserAgent, LibreChatHttpClient.BROWSER_USER_AGENT)
}
