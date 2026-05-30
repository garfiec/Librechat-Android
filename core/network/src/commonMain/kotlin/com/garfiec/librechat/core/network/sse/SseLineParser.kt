package com.garfiec.librechat.core.network.sse

import co.touchlab.kermit.Logger
import com.garfiec.librechat.core.logging.Diag
import com.garfiec.librechat.core.logging.LogOrigin
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout

class SseLineParser(
    private val lineReadTimeoutMs: Long = DEFAULT_LINE_READ_TIMEOUT_MS,
) {

    fun parse(channel: ByteReadChannel): Flow<SseEvent> = flow {
        var currentEvent = ""
        val currentData = StringBuilder()

        try {
            while (!channel.isClosedForRead) {
                val line = try {
                    withTimeout(lineReadTimeoutMs) {
                        channel.readUTF8Line()
                    }
                } catch (e: TimeoutCancellationException) {
                    Diag.w(
                        "SSE",
                        origin = LogOrigin.NETWORK,
                        attrs = mapOf("timeoutSec" to (lineReadTimeoutMs / 1000).toString()),
                    ) { "SSE stream stalled: no data" }
                    throw SseStreamException("Stream stalled: no data received for ${lineReadTimeoutMs / 1000}s", e)
                } ?: break

                when {
                    // Comment lines (keepalive pings)
                    line.startsWith(":") -> continue

                    // Event type
                    line.startsWith("event:") -> {
                        currentEvent = line.removePrefix("event:").trim()
                    }

                    // Data lines - support multi-line data by appending with newline
                    line.startsWith("data:") -> {
                        val data = line.removePrefix("data:").trim()
                        if (currentData.isNotEmpty()) {
                            currentData.append("\n")
                        }
                        currentData.append(data)
                    }

                    // Empty line = end of event
                    line.isBlank() -> {
                        if (currentData.isNotEmpty()) {
                            val data = currentData.toString()
                            if (data == "[DONE]") {
                                currentEvent = ""
                                currentData.clear()
                                break
                            }
                            emit(SseEvent(event = currentEvent, data = data))
                            currentEvent = ""
                            currentData.clear()
                        }
                    }
                }
            }
        } catch (e: SseStreamException) {
            Logger.e("SSE", e) { "SSE parse error" }
            throw e
        } catch (e: Exception) {
            Logger.e("SSE", e) { "SSE parse error" }
            throw e
        }

        // Emit any remaining buffered data
        if (currentData.isNotEmpty()) {
            val data = currentData.toString()
            if (data != "[DONE]") {
                emit(SseEvent(event = currentEvent, data = data))
            }
        }
    }

    companion object {
        /** Default timeout: 120 seconds to accommodate long AI thinking/agent blocks. */
        const val DEFAULT_LINE_READ_TIMEOUT_MS = 120_000L
    }
}
