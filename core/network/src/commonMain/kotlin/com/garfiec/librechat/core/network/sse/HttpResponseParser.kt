package com.garfiec.librechat.core.network.sse

/**
 * Minimal, stateful HTTP/1.1 response parser for the iOS raw-socket SSE transport.
 *
 * Lives in commonMain so it can be JVM-unit-tested without platform deps. Phase 2b's
 * iOS NWConnection transport feeds raw TCP bytes into this parser; Android does NOT
 * use it (OkHttp handles framing itself).
 *
 * Wire format we target (confirmed in the Phase 0 spike):
 *   HTTP/1.1 200 OK\r\n
 *   Content-Type: text/event-stream\r\n
 *   Transfer-Encoding: chunked\r\n
 *   Content-Encoding: identity\r\n
 *   Connection: close\r\n
 *   Cache-Control: no-cache, no-transform\r\n
 *   \r\n
 *   <hex-size>[; ext...]\r\n
 *   <bytes>\r\n
 *   ... repeated ...
 *   0\r\n
 *   \r\n
 *
 * Handled:
 *  - Status line parsing (`HTTP/1.1 <code> <reason>`)
 *  - Header folding into a lower-cased map (case-insensitive lookup)
 *  - Chunked transfer-encoding with hex size lines and optional chunk extensions
 *    (everything after the first `;` on the size line is ignored)
 *  - Identity body (pass-through) used only as a fallback for non-chunked responses
 *  - Arbitrary feed boundaries: a single logical line may arrive across many feeds
 *  - Final `0\r\n\r\n` terminator emits EndOfStream
 *
 * NOT handled — by design, because LibreChat+Cloudflare never send these to us:
 *  - Chunk trailers (the bytes between the final `0\r\n` and the terminating `\r\n`)
 *  - Content-Encoding other than identity (we send `Accept-Encoding: identity`)
 *  - HTTP/1.0 "connection close = EOF" without Content-Length / Transfer-Encoding
 *  - Redirects (3xx) — caller must handle status code if it ever sees one
 *  - Pipelined responses — one request, one response, done
 *
 * Usage:
 *   val parser = HttpResponseParser()
 *   socket.read { bytes ->
 *       parser.feed(bytes).forEach { event ->
 *           when (event) { ... }
 *       }
 *   }
 */
class HttpResponseParser {

    sealed class ParseEvent {
        data class HeadersComplete(
            val statusCode: Int,
            val headers: Map<String, String>,
        ) : ParseEvent()

        data class BodyChunk(val bytes: ByteArray) : ParseEvent() {
            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (other !is BodyChunk) return false
                return bytes.contentEquals(other.bytes)
            }

            override fun hashCode(): Int = bytes.contentHashCode()
        }

        data object EndOfStream : ParseEvent()

        data class Error(val message: String) : ParseEvent()
    }

    private enum class State {
        READING_STATUS_LINE,
        READING_HEADERS,
        READING_BODY,
        DONE,
        FAILED,
    }

    private enum class BodyMode { UNKNOWN, IDENTITY, CHUNKED }

    private enum class ChunkState {
        READING_SIZE_LINE,
        READING_DATA,
        READING_TRAILING_CRLF,
        READING_FINAL_CRLF,
    }

    private var state: State = State.READING_STATUS_LINE
    private var bodyMode: BodyMode = BodyMode.UNKNOWN
    private var chunkState: ChunkState = ChunkState.READING_SIZE_LINE
    private var remainingChunkBytes: Int = 0

    // Raw byte accumulator. We append on feed() and drain-in-place as we parse.
    // Simple ArrayList<Byte> keeps the code platform-agnostic; byte counts per
    // SSE frame are small (a few KB) so the copy-cost is negligible.
    private val buffer: ArrayList<Byte> = ArrayList(DEFAULT_BUFFER_CAPACITY)

    private val headers: MutableMap<String, String> = mutableMapOf()
    private var statusCode: Int = 0

    fun feed(bytes: ByteArray): List<ParseEvent> {
        if (state == State.DONE || state == State.FAILED) return emptyList()
        if (bytes.isNotEmpty()) {
            buffer.ensureCapacity(buffer.size + bytes.size)
            for (b in bytes) buffer.add(b)
        }
        val events = mutableListOf<ParseEvent>()
        drain(events)
        return events
    }

    private fun drain(out: MutableList<ParseEvent>) {
        while (true) {
            when (state) {
                State.READING_STATUS_LINE -> {
                    val line = readLine() ?: return
                    if (!parseStatusLine(line, out)) return
                    state = State.READING_HEADERS
                }

                State.READING_HEADERS -> {
                    val line = readLine() ?: return
                    if (line.isEmpty()) {
                        // end of headers
                        finalizeHeaders(out)
                        if (state == State.FAILED || state == State.DONE) return
                    } else {
                        if (!parseHeaderLine(line, out)) return
                    }
                }

                State.READING_BODY -> {
                    val progressed = when (bodyMode) {
                        BodyMode.CHUNKED -> drainChunkedBody(out)
                        BodyMode.IDENTITY -> drainIdentityBody(out)
                        BodyMode.UNKNOWN -> {
                            fail(out, "body mode not determined at READING_BODY")
                            return
                        }
                    }
                    if (!progressed) return
                }

                State.DONE, State.FAILED -> return
            }
        }
    }

    private fun parseStatusLine(line: String, out: MutableList<ParseEvent>): Boolean {
        // Expected shape: "HTTP/1.1 200 OK" or "HTTP/1.0 200 Ok"
        // Tolerate any single-space separators; reject anything else.
        val parts = line.split(' ', limit = 3)
        if (parts.size < 2) {
            fail(out, "malformed status line: '$line'")
            return false
        }
        val version = parts[0]
        if (!version.startsWith("HTTP/1.")) {
            fail(out, "unsupported HTTP version: '$version'")
            return false
        }
        val code = parts[1].toIntOrNull()
        if (code == null || code < 100 || code > 599) {
            fail(out, "invalid status code: '${parts[1]}'")
            return false
        }
        statusCode = code
        return true
    }

    private fun parseHeaderLine(line: String, out: MutableList<ParseEvent>): Boolean {
        val colon = line.indexOf(':')
        if (colon <= 0) {
            fail(out, "malformed header line: '$line'")
            return false
        }
        val name = line.substring(0, colon).trim().lowercase()
        val value = line.substring(colon + 1).trim()
        // Repeated field lines fold into one comma-separated value (RFC 7230 §3.2.2). Not last-wins:
        // the gateway check reads `WWW-Authenticate`, which is a list and can carry the Access
        // challenge on any line — dropping a line makes a blocked stream look healthy.
        val existing = headers[name]
        headers[name] = if (existing == null) value else "$existing, $value"
        return true
    }

    private fun finalizeHeaders(out: MutableList<ParseEvent>) {
        val transferEncoding = headers["transfer-encoding"]?.lowercase()
        bodyMode = if (transferEncoding != null && transferEncoding.contains("chunked")) {
            BodyMode.CHUNKED
        } else {
            BodyMode.IDENTITY
        }
        out.add(ParseEvent.HeadersComplete(statusCode, headers.toMap()))
        state = State.READING_BODY
        chunkState = ChunkState.READING_SIZE_LINE
    }

    /**
     * Returns true if parsing made forward progress (consumed at least one byte or
     * emitted at least one event). Returns false if the body is starved — caller's
     * drain() loop treats false as "wait for more bytes".
     */
    private fun drainChunkedBody(out: MutableList<ParseEvent>): Boolean {
        when (chunkState) {
            ChunkState.READING_SIZE_LINE -> {
                val line = readLine() ?: return false
                val semi = line.indexOf(';')
                val sizeToken = if (semi >= 0) line.substring(0, semi).trim() else line.trim()
                val size = parseHexSize(sizeToken)
                if (size == null) {
                    fail(out, "invalid chunk size: '$sizeToken'")
                    return false
                }
                remainingChunkBytes = size
                chunkState = if (size == 0) ChunkState.READING_FINAL_CRLF else ChunkState.READING_DATA
                return true
            }

            ChunkState.READING_DATA -> {
                if (buffer.isEmpty()) return false
                val take = minOf(remainingChunkBytes, buffer.size)
                val slice = ByteArray(take)
                for (i in 0 until take) slice[i] = buffer[i]
                removeFromBufferHead(take)
                remainingChunkBytes -= take
                if (slice.isNotEmpty()) out.add(ParseEvent.BodyChunk(slice))
                if (remainingChunkBytes == 0) {
                    chunkState = ChunkState.READING_TRAILING_CRLF
                }
                return true
            }

            ChunkState.READING_TRAILING_CRLF -> {
                // After each non-terminal chunk's data, the protocol requires a CRLF.
                val line = readLine() ?: return false
                if (line.isNotEmpty()) {
                    fail(out, "expected CRLF after chunk data, got: '$line'")
                    return false
                }
                chunkState = ChunkState.READING_SIZE_LINE
                return true
            }

            ChunkState.READING_FINAL_CRLF -> {
                // After the `0\r\n` terminator line, we expect one more empty line
                // that closes the message (no trailers supported). Then EndOfStream.
                val line = readLine() ?: return false
                if (line.isNotEmpty()) {
                    fail(out, "expected CRLF after final chunk, got: '$line'")
                    return false
                }
                out.add(ParseEvent.EndOfStream)
                state = State.DONE
                return true
            }
        }
    }

    private fun drainIdentityBody(out: MutableList<ParseEvent>): Boolean {
        if (buffer.isEmpty()) return false
        val slice = ByteArray(buffer.size)
        for (i in buffer.indices) slice[i] = buffer[i]
        buffer.clear()
        out.add(ParseEvent.BodyChunk(slice))
        return true
    }

    /**
     * Read a single CRLF-terminated line from the buffer. Consumes the CRLF.
     * Returns null if no complete line is available yet (caller should wait for
     * more bytes). Tolerates bare `\n` as a line terminator because we don't
     * control every intermediary.
     */
    private fun readLine(): String? {
        var i = 0
        while (i < buffer.size) {
            val b = buffer[i].toInt() and 0xFF
            if (b == LF) {
                // Line ends here. If the previous byte is CR, trim it.
                val endExclusive = if (i > 0 && (buffer[i - 1].toInt() and 0xFF) == CR) i - 1 else i
                val bytes = ByteArray(endExclusive)
                for (j in 0 until endExclusive) bytes[j] = buffer[j]
                removeFromBufferHead(i + 1)
                return bytes.decodeToString()
            }
            i++
        }
        return null
    }

    private fun removeFromBufferHead(count: Int) {
        if (count <= 0) return
        if (count >= buffer.size) {
            buffer.clear()
            return
        }
        val remaining = buffer.size - count
        for (i in 0 until remaining) {
            buffer[i] = buffer[i + count]
        }
        // Truncate the tail. ArrayList has no public truncate but removeAt from the
        // end in a loop is O(n) with cheap ops; for our sizes this is fine.
        while (buffer.size > remaining) {
            buffer.removeAt(buffer.size - 1)
        }
    }

    private fun parseHexSize(token: String): Int? {
        if (token.isEmpty() || token.length > HEX_SIZE_MAX_DIGITS) return null
        var result = 0
        for (ch in token) {
            val digit = when (ch) {
                in '0'..'9' -> ch - '0'
                in 'a'..'f' -> ch - 'a' + 10
                in 'A'..'F' -> ch - 'A' + 10
                else -> return null
            }
            result = (result shl 4) or digit
            if (result < 0) return null // overflow guard for pathologically huge chunks
        }
        return result
    }

    private fun fail(out: MutableList<ParseEvent>, message: String) {
        state = State.FAILED
        out.add(ParseEvent.Error(message))
    }

    private companion object {
        const val DEFAULT_BUFFER_CAPACITY = 8192
        const val CR = 0x0D
        const val LF = 0x0A
        const val HEX_SIZE_MAX_DIGITS = 8 // 0xFFFFFFFF — plenty for our chunks
    }
}
