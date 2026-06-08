package com.garfiec.librechat.core.network.sse

import co.touchlab.kermit.Logger
import com.garfiec.librechat.core.logging.Diag
import com.garfiec.librechat.core.logging.LogOrigin
import com.garfiec.librechat.core.model.StreamEvent
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlin.math.min

class SseClient(
    private val json: Json,
    private val transport: SseHttpTransport,
) {
    private val mapper = SseEventMapper(json)
    private val lineParser = SseLineParser()

    /**
     * @param connectivityFlow Optional flow of network connectivity state. When provided,
     *   retries will wait for the network to become available before attempting reconnection,
     *   avoiding wasted retry attempts while offline.
     */
    fun connect(
        streamPath: String,
        resume: Boolean = false,
        connectivityFlow: Flow<Boolean>? = null,
    ): Flow<StreamEvent> = flow {
        // Outer try/catch ensures no exception escapes the flow.
        // SKIE's Flow→AsyncSequence iterator calls fatalError on unexpected errors,
        // so any Kotlin exception that escapes this flow will crash the iOS app.
        try {
        mapper.resetState()
        var attempt = 0
        var shouldResume = resume
        var done = false
        val maxRetries = 5
        val initialDelayMs = 1000L
        val maxDelayMs = 30_000L

        while (attempt <= maxRetries && !done) {
            try {
                val byteChannel = ByteChannel(autoFlush = true)
                coroutineScope {
                    val pumpJob = launch {
                        try {
                            transport.stream(streamPath, shouldResume).collect { bytes ->
                                attempt = 0
                                byteChannel.writeFully(bytes)
                            }
                            byteChannel.flushAndClose()
                        } catch (e: CancellationException) {
                            byteChannel.cancel(e)
                            throw e
                        } catch (e: Exception) {
                            byteChannel.cancel(e)
                            throw e
                        }
                    }
                    try {
                        lineParser.parse(byteChannel).collect { sseEvent ->
                            // One frame can carry multiple events (the resume `sync`
                            // frame expands to a snapshot + its buffered pendingEvents),
                            // so map to a list and emit each in order.
                            mapper.mapFrame(sseEvent).forEach { streamEvent ->
                                emit(streamEvent)
                                if (streamEvent is StreamEvent.Final) {
                                    done = true
                                }
                            }
                        }
                    } finally {
                        pumpJob.cancel()
                    }
                }
                done = true
            } catch (e: SseHttpStatusException) {
                when (e.statusCode) {
                    HttpStatusCode.NotFound.value -> {
                        done = true
                    }

                    HttpStatusCode.Unauthorized.value -> {
                        Diag.w(
                            "SSE",
                            origin = LogOrigin.SERVER,
                            attrs = mapOf(
                                "status" to e.statusCode.toString(),
                                "attempt" to attempt.toString(),
                            ),
                        ) { "SSE 401 Unauthorized" }
                        emit(StreamEvent.Error(message = "Unauthorized", code = "401"))
                        done = true
                    }

                    else -> {
                        Diag.w(
                            "SSE",
                            origin = LogOrigin.SERVER,
                            attrs = mapOf(
                                "status" to e.statusCode.toString(),
                                "attempt" to attempt.toString(),
                            ),
                        ) { "SSE unexpected status" }
                        attempt++
                    }
                }
            } catch (e: SseStreamException) {
                Diag.w(
                    "SSE",
                    origin = LogOrigin.NETWORK,
                    throwable = e,
                    attrs = mapOf("attempt" to attempt.toString()),
                ) { "SSE I/O error" }
                attempt++
                if (attempt > maxRetries) {
                    emit(StreamEvent.Error(message = "Connection lost. Please check your network and try again.", isNetworkError = true))
                    done = true
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Diag.w(
                    "SSE",
                    origin = LogOrigin.NETWORK,
                    throwable = e,
                    attrs = mapOf("attempt" to attempt.toString()),
                ) { "SSE connection error" }
                attempt++
                if (attempt > maxRetries) {
                    emit(StreamEvent.Error(message = "Connection failed. Please try again."))
                    done = true
                }
            }

            if (!done) {
                emit(StreamEvent.Retrying(attempt = attempt, maxAttempts = maxRetries))

                if (connectivityFlow != null) {
                    try {
                        val isConnected = connectivityFlow.first()
                        if (!isConnected) {
                            Logger.d("SSE") { "SSE: network is down, waiting for connectivity before retry $attempt" }
                            connectivityFlow.first { it }
                            Logger.d("SSE") { "SSE: network restored, proceeding with retry $attempt" }
                        }
                    } catch (e: Exception) {
                        Logger.w("SSE", e) { "SSE: error checking connectivity, falling back to delay" }
                    }
                }

                val delayMs = min(initialDelayMs * (1L shl (attempt - 1)), maxDelayMs)
                delay(delayMs)
                shouldResume = true
            }
        }
        } catch (e: CancellationException) {
            throw e // Re-throw cancellation — SKIE handles this gracefully
        } catch (e: Exception) {
            Diag.e(
                "SSE",
                origin = LogOrigin.CLIENT,
                throwable = e,
            ) { "SSE unhandled exception escaped flow" }
            emit(StreamEvent.Error(message = "Unexpected error: ${e.message}"))
        }
    }
}
