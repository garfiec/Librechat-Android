package com.garfiec.librechat.core.network.sse

import co.touchlab.kermit.Logger
import com.garfiec.librechat.core.network.client.BearerResult
import com.garfiec.librechat.core.network.client.LibreChatHttpClient
import com.garfiec.librechat.core.network.client.SwitchGate
import com.garfiec.librechat.core.network.client.TokenManager
import com.garfiec.librechat.core.network.client.refreshBearerFor
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import platform.Foundation.NSURL
import platform.Network.nw_connection_cancel
import platform.Network.nw_connection_create
import platform.Network.nw_connection_force_cancel
import platform.Network.nw_connection_receive
import platform.Network.nw_connection_send
import platform.Network.nw_connection_set_queue
import platform.Network.nw_connection_set_state_changed_handler
import platform.Network.nw_connection_start
import platform.Network.nw_connection_state_cancelled
import platform.Network.nw_connection_state_failed
import platform.Network.nw_connection_state_ready
import platform.Network.nw_connection_state_t
import platform.Network.nw_connection_t
import platform.Network.nw_content_context_t
import platform.Network.nw_endpoint_create_host
import platform.Network.nw_error_get_error_code
import platform.Network.nw_error_get_error_domain
import platform.Network.nw_error_domain_posix
import platform.Network.nw_error_domain_tls
import platform.Network.nw_error_t
import platform.Network.nw_parameters_t
import com.garfiec.librechat.core.network.sse.nwparams.librechat_make_default_tls_tcp_parameters
import platform.darwin.dispatch_data_create
import platform.darwin.dispatch_data_create_map
import platform.darwin.dispatch_data_get_size
import platform.darwin.dispatch_data_t
import platform.darwin.dispatch_get_global_queue
import platform.darwin.dispatch_queue_create
import platform.posix.ECONNRESET
import platform.posix.EPIPE
import platform.posix.ETIMEDOUT
import platform.posix.size_tVar

// iOS SSE transport that bypasses NSURLSession entirely.
//
// Why this exists:
// NSURLSession has an undocumented buffering behavior where
// `URLSession:dataTask:didReceiveData:` is not called for `text/*`
// Content-Type responses until 512+ bytes have been received OR the
// connection closes. `application/json` and `application/octet-stream`
// are exempt — they stream byte-by-byte. LibreChat's SSE endpoint sends
// `Content-Type: text/event-stream`, so on iOS the chat response would
// never appear until the user pressed stop (which closes the connection
// and flushes NSURLSession's buffer).
//
// This is acknowledged but unfixed Apple behavior — see Apple Developer
// Forums thread 64875 (open since 2016, last confirmed Feb 2025).
// Tracked on the Ktor side as KTOR-6378 "Darwin: The engine doesn't
// stream chunked responses with small chunks", status: Unresolved.
//
// The fix CANNOT live in Ktor or in commonMain because the buffering
// happens below Ktor — Ktor's Darwin engine faithfully forwards every
// didReceiveData callback it gets, but NSURLSession isn't calling it.
// The Android prepareGet+execute workaround (commit f182b2b) only
// addresses Ktor-layer buffering, which is a different layer.
//
// This implementation uses Network.framework's NWConnection to open a
// raw TCP+TLS connection and speak HTTP/1.1 directly, avoiding
// NSURLSession entirely for the SSE endpoint only. All other HTTP
// traffic (JSON requests, auth, conversation list, etc.) continues to
// use the Darwin engine, which works fine for non-text/* responses.
//
// Trade-offs vs NSURLSession:
// - No App Transport Security enforcement (Network.framework doesn't
//   gate on ATS the way URLSession does). Acceptable for a "bring your
//   own server" app where users may run LibreChat on local IPs.
// - No automatic system proxy detection. If we ever need it, we'll
//   read the system proxy config manually.
// - Cert pinning, when added later, must be wired up here separately
//   from the NSURLSessionDelegate-based approach used by the Darwin
//   engine.
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
actual class SseHttpTransport(
    private val tokenManager: TokenManager,
    private val switchGate: SwitchGate,
) {

    actual fun stream(streamPath: String, resume: Boolean): Flow<ByteArray> = flow {
        // Capture the (baseUrl, account, bearer) triple ONCE before opening the raw connection. This
        // is the SwitchBarrierPlugin equivalent for the NWConnection path (which can't use Ktor
        // plugins): a switch mid-stream can't tear the URL and token apart — the stream keeps running
        // against the account and server it started on, whose tokens are retained.
        val snapshot = switchGate.captureSnapshot()
        var token = snapshot.bearer
        var triedRefresh = false
        while (true) {
            try {
                emitAll(openConnection(streamPath, resume, snapshot.baseUrl, token))
                return@flow
            } catch (e: SseHttpStatusException) {
                if (e.statusCode == 401 && !triedRefresh) {
                    Logger.w("SSE-iOS") { "401 on SSE stream, attempting token refresh" }
                    triedRefresh = true
                    // Refresh the snapshot's account (keyed + URL-pinned), never the live active one;
                    // an expiry is likewise scoped to the snapshot's account, so a switched-away
                    // stream's dead credentials can't tear down the live account's session.
                    when (val refresh = tokenManager.refreshBearerFor(snapshot)) {
                        is BearerResult.Refreshed -> {
                            token = refresh.token
                            // loop to retry with new token
                        }
                        BearerResult.Expired -> {
                            Logger.w("SSE-iOS") { "token refresh failed — session expired" }
                            tokenManager.emitSessionExpired(snapshot.accountId)
                            throw e
                        }
                        BearerResult.Transient -> {
                            // Recoverable refresh failure — surface the stream error without tearing
                            // down the session; a fresh stream attempt can recover.
                            Logger.w("SSE-iOS") { "token refresh transient — not logging out" }
                            throw e
                        }
                    }
                } else {
                    throw e
                }
            }
        }
    }

    private fun openConnection(
        streamPath: String,
        resume: Boolean,
        snapshotBaseUrl: String,
        bearerToken: String?,
    ): Flow<ByteArray> = callbackFlow {
        // Normalize base URL + stream path so there's exactly one slash between
        // them. The base URL comes from the snapshot captured before this connection
        // (already awaited past cold-start warm-up in SwitchGate.captureSnapshot), so
        // a switch can't repoint it under a live stream. SseClient passes the stream
        // path without a leading slash, matching how ChatRepositoryImpl builds it
        // ("api/agents/chat/stream/$streamId").
        val baseUrl = snapshotBaseUrl.trimEnd('/')
        val normalizedPath = streamPath.trimStart('/')
        val queryString = if (resume) "?resume=true" else ""
        val fullUrl = "$baseUrl/$normalizedPath$queryString"
        val url = NSURL.URLWithString(fullUrl)
            ?: run {
                close(SseStreamException("invalid SSE URL: $fullUrl"))
                return@callbackFlow
            }

        val scheme = url.scheme?.lowercase() ?: "https"
        // The iOS NWConnection transport only supports HTTPS. Plain HTTP would
        // require passing NW_PARAMETERS_DISABLE_PROTOCOL for the TLS layer,
        // which is a non-null block sentinel that we cannot construct from
        // Kotlin/Native without an Obj-C/Swift shim. Production LibreChat
        // deployments run HTTPS (Cloudflare, reverse proxies, etc.), so we
        // refuse non-HTTPS here instead of silently misconfiguring. File an
        // issue if you actually need plain HTTP support.
        if (scheme != "https") {
            close(SseStreamException("iOS SSE transport only supports HTTPS (got scheme=$scheme)"))
            return@callbackFlow
        }
        val host = url.host ?: run {
            close(SseStreamException("missing host in SSE URL: $url"))
            return@callbackFlow
        }
        val port = (url.port?.intValue ?: DEFAULT_HTTPS_PORT).toUShort()
        val requestPath = buildRequestPath(url)

        val endpoint = nw_endpoint_create_host(host, port.toString())
        // Default TLS+TCP parameters for NWConnection must come from a C shim.
        //
        // `nw_parameters_create_secure_tcp` takes two `configure` block-pointer
        // arguments. Apple exposes `NW_PARAMETERS_DEFAULT_CONFIGURATION` (which
        // expands to the exported global `_nw_parameters_configure_protocol_default_configuration`)
        // as the "no-op / use defaults" sentinel. We need to pass that sentinel
        // here, but we cannot reach it from Kotlin without crashing:
        //
        // - A Kotlin lambda gets wrapped by Kotlin/Native's cinterop bridge
        //   into a synthetic Obj-C block whose parameter type is `NSObject?`.
        //   Network.framework then invokes the block with a runtime argument
        //   of type `Network.ProtocolOptions<Network.TLSProtocol>` — a Swift
        //   generic class that does NOT inherit from NSObject on modern iOS.
        //   The generated teardown path (`block_destroy_helper`) tries to cast
        //   the Swift generic to NSObject and crashes with
        //   `TypeCastException: _TtGC7Network15ProtocolOptionsVS_11TLSProtocol_
        //   cannot be cast to class platform.darwin.NSObject`.
        //
        // - Kotlin/Native 2.3.20's platform.Network binding for
        //   `NW_PARAMETERS_DEFAULT_CONFIGURATION` is a `knifunptr_*` getter
        //   that returns a Kotlin-wrapped lambda around Apple's block pointer.
        //   Passing that through `nw_parameters_create_secure_tcp` re-bridges
        //   it as a fresh synthetic Obj-C block, and the same
        //   `block_destroy_helper` / `knbridge3` crash fires.
        //
        // The only fix is a C cinterop shim that calls the macro at C compile
        // time and hands the block pointer directly to Network.framework with
        // no Kotlin code in the middle. That shim lives in
        // `src/iosMain/cinterop/nwparams_defaults.def` and exposes the
        // function `librechat_make_default_tls_tcp_parameters`.
        val parameters: nw_parameters_t = librechat_make_default_tls_tcp_parameters()
            ?: run {
                close(SseStreamException("nw_parameters_create_secure_tcp returned null"))
                return@callbackFlow
            }

        val connection: nw_connection_t = nw_connection_create(endpoint, parameters)
            ?: run {
                close(SseStreamException("nw_connection_create returned null"))
                return@callbackFlow
            }

        val queue = dispatch_queue_create("com.garfiec.librechat.sse.nwconnection", null)
        val parser = HttpResponseParser()
        var handled = false

        val handleError: (Throwable) -> Unit = { err ->
            if (!handled) {
                handled = true
                close(err)
                nw_connection_cancel(connection)
            }
        }

        val handleCompletion: () -> Unit = {
            if (!handled) {
                handled = true
                close(null)
                nw_connection_cancel(connection)
            }
        }

        // Recursive receive loop. Each call to nw_connection_receive arms a
        // single-shot callback; we re-arm inside the callback until isComplete
        // or an error fires.
        fun armReceive() {
            nw_connection_receive(
                connection = connection,
                minimum_incomplete_length = 1u,
                maximum_length = RECEIVE_MAX_LENGTH.toUInt(),
            ) { data, _, isComplete, error ->
                if (handled) return@nw_connection_receive

                if (error != null) {
                    handleError(mapNwError(error))
                    return@nw_connection_receive
                }

                if (data != null) {
                    val bytes = dispatchDataToByteArray(data)
                    if (bytes != null && bytes.isNotEmpty()) {
                        val events = parser.feed(bytes)
                        for (event in events) {
                            when (event) {
                                is HttpResponseParser.ParseEvent.HeadersComplete -> {
                                    if (event.statusCode !in SUCCESS_LOW..SUCCESS_HIGH) {
                                        handleError(SseHttpStatusException(event.statusCode))
                                        return@nw_connection_receive
                                    }
                                }

                                is HttpResponseParser.ParseEvent.BodyChunk -> {
                                    trySend(event.bytes)
                                }

                                HttpResponseParser.ParseEvent.EndOfStream -> {
                                    handleCompletion()
                                    return@nw_connection_receive
                                }

                                is HttpResponseParser.ParseEvent.Error -> {
                                    handleError(SseStreamException("parser: ${event.message}"))
                                    return@nw_connection_receive
                                }
                            }
                        }
                    }
                }

                if (isComplete) {
                    handleCompletion()
                    return@nw_connection_receive
                }

                armReceive()
            }
        }

        nw_connection_set_state_changed_handler(connection) { state, error ->
            when (state) {
                nw_connection_state_ready -> {
                    val request = buildHttpRequest(
                        path = requestPath,
                        host = host,
                        bearerToken = bearerToken,
                    )
                    val requestBytes = request.encodeToByteArray()
                    val dispatchData = byteArrayToDispatchData(requestBytes, queue)
                    if (dispatchData == null) {
                        handleError(SseStreamException("failed to create dispatch_data for request"))
                        return@nw_connection_set_state_changed_handler
                    }
                    nw_connection_send(
                        connection = connection,
                        content = dispatchData,
                        context = NW_CONTENT_CONTEXT_DEFAULT_MESSAGE,
                        is_complete = true,
                        completion = { sendError ->
                            if (sendError != null) {
                                handleError(mapNwError(sendError))
                            } else {
                                armReceive()
                            }
                        },
                    )
                }

                nw_connection_state_failed -> {
                    val mapped = error?.let { mapNwError(it) }
                        ?: SseStreamException("nw_connection failed with no error")
                    handleError(mapped)
                }

                nw_connection_state_cancelled -> {
                    handleCompletion()
                }

                else -> {
                    // preparing / waiting / invalid — nothing to do; we wait for ready/failed
                }
            }
        }

        nw_connection_set_queue(connection, queue)
        nw_connection_start(connection)

        awaitClose {
            if (!handled) {
                handled = true
            }
            nw_connection_cancel(connection)
            // force_cancel ensures pending receives abort immediately so the
            // coroutine frame can tear down promptly.
            nw_connection_force_cancel(connection)
        }
    }

    private fun buildRequestPath(url: NSURL): String {
        val path = url.path ?: "/"
        val query = url.query
        val effectivePath = if (path.isEmpty()) "/" else path
        return if (query.isNullOrEmpty()) effectivePath else "$effectivePath?$query"
    }

    private fun buildHttpRequest(
        path: String,
        host: String,
        bearerToken: String?,
    ): String = buildString {
        append("GET ").append(path).append(" HTTP/1.1\r\n")
        append("Host: ").append(host).append("\r\n")
        append("Accept: text/event-stream\r\n")
        if (bearerToken != null) {
            append("Authorization: Bearer ").append(bearerToken).append("\r\n")
        }
        append("User-Agent: ").append(LibreChatHttpClient.BROWSER_USER_AGENT).append("\r\n")
        append("Accept-Encoding: identity\r\n")
        append("Connection: close\r\n")
        append("\r\n")
    }

    private fun mapNwError(error: nw_error_t): Throwable {
        val domain = nw_error_get_error_domain(error)
        val code = nw_error_get_error_code(error).toInt()
        return when (domain) {
            nw_error_domain_posix -> {
                // ECONNRESET / EPIPE / ETIMEDOUT are retryable — let SseClient's
                // exponential backoff retry loop handle them via SseStreamException.
                if (code == ECONNRESET || code == EPIPE || code == ETIMEDOUT) {
                    SseStreamException("network error (posix $code)")
                } else {
                    SseStreamException("network error (posix $code)")
                }
            }

            nw_error_domain_tls -> {
                // TLS errors (bad cert, handshake failure, pinning failure) are
                // non-retryable — the operator has to fix the cert. Wrap as a
                // plain Exception so SseClient's retry loop falls through to the
                // generic-error branch and shows a user-facing error.
                Exception("TLS error (code $code) — check the server certificate")
            }

            else -> {
                // dns, other, invalid — retryable in spirit, not worth differentiating.
                SseStreamException("nw_error domain=$domain code=$code")
            }
        }
    }

    private fun dispatchDataToByteArray(data: dispatch_data_t): ByteArray? {
        val size = dispatch_data_get_size(data).toInt()
        if (size == 0) return ByteArray(0)
        return memScoped {
            // dispatch_data_create_map returns a new contiguous dispatch_data whose
            // backing storage is guaranteed contiguous, and fills in `bufferPtr` /
            // `sizePtr` with a pointer+length into that storage. The returned
            // dispatch_data_t must outlive our read of the pointer, which it does
            // because we copy bytes out before leaving this scope.
            val sizeOut = alloc<size_tVar>()
            val bufferOut = alloc<kotlinx.cinterop.COpaquePointerVar>()
            val mapped = dispatch_data_create_map(data, bufferOut.ptr, sizeOut.ptr)
            mapped ?: return@memScoped null
            val mappedSize = sizeOut.value.toInt()
            val ptr = bufferOut.value ?: return@memScoped null
            ptr.readBytes(mappedSize)
        }
    }

    private fun byteArrayToDispatchData(bytes: ByteArray, queue: platform.darwin.dispatch_queue_t): dispatch_data_t? {
        if (bytes.isEmpty()) {
            return dispatch_data_create(null, 0u, queue, null)
        }
        return bytes.usePinned { pinned ->
            // Passing null for the destructor block tells dispatch to use
            // DISPATCH_DATA_DESTRUCTOR_DEFAULT, which copies the buffer into
            // its own allocation so we can release the pin after this call.
            dispatch_data_create(
                pinned.addressOf(0),
                bytes.size.convert(),
                queue,
                null,
            )
        }
    }

    private companion object {
        const val DEFAULT_HTTPS_PORT = 443
        const val RECEIVE_MAX_LENGTH = 16384
        const val SUCCESS_LOW = 200
        const val SUCCESS_HIGH = 299

        // NW_CONTENT_CONTEXT_DEFAULT_MESSAGE — passed to nw_connection_send as
        // the context argument; it represents a fresh message context. In
        // Kotlin/Native this surfaces as a global that we resolve by name at
        // link time; for our one-shot request we can pass null and let the
        // default-message context apply automatically (Apple docs confirm
        // null is equivalent to default-message for the send path).
        val NW_CONTENT_CONTEXT_DEFAULT_MESSAGE: nw_content_context_t? = null
    }
}
