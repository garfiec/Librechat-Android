package com.garfiec.librechat.core.network.sse

import com.garfiec.librechat.core.network.client.AccessGatewaySignal
import com.garfiec.librechat.core.network.sse.HttpResponseParser.ParseEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class HttpResponseParserTest {

    private fun String.bytes(): ByteArray = encodeToByteArray()

    private fun collectBodyBytes(events: List<ParseEvent>): ByteArray {
        val out = mutableListOf<Byte>()
        for (e in events) {
            if (e is ParseEvent.BodyChunk) {
                for (b in e.bytes) out.add(b)
            }
        }
        return ByteArray(out.size) { out[it] }
    }

    @Test
    fun statusLineParsesOk() {
        val parser = HttpResponseParser()
        val events = parser.feed("HTTP/1.1 200 OK\r\n\r\n".bytes())
        // No body-mode determined -> falls through identity on empty feed, so the only
        // event we should see is HeadersComplete.
        val headers = events.filterIsInstance<ParseEvent.HeadersComplete>().single()
        assertEquals(200, headers.statusCode)
        assertTrue(headers.headers.isEmpty())
    }

    @Test
    fun statusLineWithHttp10IsAccepted() {
        val parser = HttpResponseParser()
        val events = parser.feed("HTTP/1.0 404 Not Found\r\n\r\n".bytes())
        val headers = events.filterIsInstance<ParseEvent.HeadersComplete>().single()
        assertEquals(404, headers.statusCode)
    }

    @Test
    fun malformedStatusLineReturnsError() {
        val parser = HttpResponseParser()
        val events = parser.feed("NOT-HTTP 200 OK\r\n".bytes())
        val err = events.filterIsInstance<ParseEvent.Error>().single()
        assertTrue(err.message.contains("HTTP"))
    }

    @Test
    fun invalidStatusCodeReturnsError() {
        val parser = HttpResponseParser()
        val events = parser.feed("HTTP/1.1 abc OK\r\n".bytes())
        assertTrue(events.any { it is ParseEvent.Error })
    }

    @Test
    fun headersParseAcrossMultipleFeeds() {
        val parser = HttpResponseParser()
        val e1 = parser.feed("HTTP/1.1 200 OK\r\nContent-Ty".bytes())
        assertTrue(e1.isEmpty())
        val e2 = parser.feed("pe: text/event-stream\r\nTrans".bytes())
        assertTrue(e2.isEmpty())
        val e3 = parser.feed("fer-Encoding: chunked\r\n\r\n".bytes())
        val hc = e3.filterIsInstance<ParseEvent.HeadersComplete>().single()
        assertEquals(200, hc.statusCode)
        assertEquals("text/event-stream", hc.headers["content-type"])
        assertEquals("chunked", hc.headers["transfer-encoding"])
    }

    @Test
    fun headerLookupIsCaseInsensitive() {
        val parser = HttpResponseParser()
        val events = parser.feed(
            "HTTP/1.1 200 OK\r\nX-Custom-Header: value\r\n\r\n".bytes(),
        )
        val hc = events.filterIsInstance<ParseEvent.HeadersComplete>().single()
        // Map stores lower-cased keys; callers can look up either way by lowercasing.
        assertEquals("value", hc.headers["x-custom-header"])
    }

    @Test
    fun chunkedSingleSmallChunk() {
        val parser = HttpResponseParser()
        val wire = "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n" +
            "5\r\nhello\r\n" +
            "0\r\n\r\n"
        val events = parser.feed(wire.bytes())
        assertIs<ParseEvent.HeadersComplete>(events[0])
        val body = collectBodyBytes(events).decodeToString()
        assertEquals("hello", body)
        assertTrue(events.any { it is ParseEvent.EndOfStream })
    }

    @Test
    fun chunkedMultipleChunksSplitAcrossFeeds() {
        val parser = HttpResponseParser()
        val events = mutableListOf<ParseEvent>()
        events += parser.feed("HTTP/1.1 200 OK\r\n".bytes())
        events += parser.feed("Transfer-Encoding: chunked\r\n\r\n".bytes())
        events += parser.feed("5\r\nhello\r\n".bytes())
        events += parser.feed("6\r\n world\r\n".bytes())
        events += parser.feed("1\r\n!\r\n".bytes())
        events += parser.feed("0\r\n\r\n".bytes())

        val hc = events.filterIsInstance<ParseEvent.HeadersComplete>().single()
        assertEquals(200, hc.statusCode)
        assertEquals("hello world!", collectBodyBytes(events).decodeToString())
        assertTrue(events.any { it is ParseEvent.EndOfStream })
    }

    @Test
    fun chunkedSizeLineWithExtensionIsAccepted() {
        val parser = HttpResponseParser()
        val wire = "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n" +
            "5;foo=bar\r\nhello\r\n" +
            "0\r\n\r\n"
        val events = parser.feed(wire.bytes())
        assertEquals("hello", collectBodyBytes(events).decodeToString())
        assertTrue(events.any { it is ParseEvent.EndOfStream })
    }

    @Test
    fun chunkedFinalZeroChunkEmitsEndOfStream() {
        val parser = HttpResponseParser()
        val wire = "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n" +
            "0\r\n\r\n"
        val events = parser.feed(wire.bytes())
        assertTrue(events.any { it is ParseEvent.EndOfStream })
        // No body chunks should have been emitted.
        assertTrue(events.none { it is ParseEvent.BodyChunk })
        // Feeding more bytes after DONE must be a no-op.
        val after = parser.feed("anything".bytes())
        assertTrue(after.isEmpty())
    }

    @Test
    fun identityBodyPassesThroughUntilCallerStops() {
        val parser = HttpResponseParser()
        val events = mutableListOf<ParseEvent>()
        events += parser.feed("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n".bytes())
        events += parser.feed("{\"a\":1}".bytes())
        events += parser.feed(",{\"b\":2}".bytes())

        val hc = events.filterIsInstance<ParseEvent.HeadersComplete>().single()
        assertEquals(200, hc.statusCode)
        assertEquals("{\"a\":1},{\"b\":2}", collectBodyBytes(events).decodeToString())
    }

    @Test
    fun bodyBytesSplitMidChunkSizeLine() {
        val parser = HttpResponseParser()
        val events = mutableListOf<ParseEvent>()
        events += parser.feed("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n".bytes())
        // Split "5\r\n" across two feeds: feed "5\r", then "\nhello\r\n"
        events += parser.feed("5\r".bytes())
        events += parser.feed("\nhello\r\n0\r\n\r\n".bytes())

        assertEquals("hello", collectBodyBytes(events).decodeToString())
        assertTrue(events.any { it is ParseEvent.EndOfStream })
    }

    @Test
    fun bodyBytesSplitMidChunkData() {
        val parser = HttpResponseParser()
        val events = mutableListOf<ParseEvent>()
        events += parser.feed("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n".bytes())
        // Split "hello" mid-data
        events += parser.feed("5\r\nhel".bytes())
        events += parser.feed("lo\r\n0\r\n\r\n".bytes())

        assertEquals("hello", collectBodyBytes(events).decodeToString())
        assertTrue(events.any { it is ParseEvent.EndOfStream })
    }

    @Test
    fun statusLineSplitMidLine() {
        val parser = HttpResponseParser()
        val e1 = parser.feed("HTTP/1.1 20".bytes())
        assertTrue(e1.isEmpty())
        val e2 = parser.feed("0 OK\r\n\r\n".bytes())
        val hc = e2.filterIsInstance<ParseEvent.HeadersComplete>().single()
        assertEquals(200, hc.statusCode)
    }

    @Test
    fun realLibreChatSseChunkIsPassedThroughVerbatim() {
        // Fixture crafted from the Phase 0 NWConnection spike — an on_run_step event
        // arriving as a single 0x167-byte chunk. The parser must NOT strip the SSE
        // framing (event:/data:/\n\n); that's SseLineParser's job downstream.
        val parser = HttpResponseParser()

        val sseBody = "event: message\n" +
            "data: {\"event\":\"on_run_step\",\"data\":{\"type\":\"tool_call\"}}\n" +
            "\n"
        val hex = sseBody.length.toString(16)

        val wire = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: text/event-stream\r\n")
            append("Transfer-Encoding: chunked\r\n")
            append("Content-Encoding: identity\r\n")
            append("Connection: close\r\n")
            append("Cache-Control: no-cache, no-transform\r\n")
            append("\r\n")
            append(hex)
            append("\r\n")
            append(sseBody)
            append("\r\n")
            append("0\r\n\r\n")
        }

        val events = parser.feed(wire.bytes())
        val hc = events.filterIsInstance<ParseEvent.HeadersComplete>().single()
        assertEquals(200, hc.statusCode)
        assertEquals("text/event-stream", hc.headers["content-type"])
        assertEquals("chunked", hc.headers["transfer-encoding"])

        val decodedBody = collectBodyBytes(events).decodeToString()
        assertEquals(sseBody, decodedBody)
        assertTrue(events.any { it is ParseEvent.EndOfStream })
    }

    @Test
    fun realLibreChatSseChunkByteByByteFeedStillParses() {
        // Same fixture, but fed one byte at a time. Exercises the worst-case
        // re-entry pattern for the state machine.
        val parser = HttpResponseParser()

        val sseBody = "event: message\ndata: {\"x\":1}\n\n"
        val hex = sseBody.length.toString(16)
        val wire = buildString {
            append("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n")
            append(hex)
            append("\r\n")
            append(sseBody)
            append("\r\n0\r\n\r\n")
        }.bytes()

        val events = mutableListOf<ParseEvent>()
        for (b in wire) {
            events += parser.feed(byteArrayOf(b))
        }

        assertEquals(200, events.filterIsInstance<ParseEvent.HeadersComplete>().single().statusCode)
        assertEquals(sseBody, collectBodyBytes(events).decodeToString())
        assertTrue(events.any { it is ParseEvent.EndOfStream })
    }

    @Test
    fun invalidChunkSizeReturnsError() {
        val parser = HttpResponseParser()
        val wire = "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n" +
            "ZZZ\r\n"
        val events = parser.feed(wire.bytes())
        assertTrue(events.any { it is ParseEvent.Error })
    }

    @Test
    fun multipleSmallChunksProduceMultipleBodyChunkEvents() {
        // This is important for streaming: one chunked frame in, one BodyChunk out,
        // so the downstream SseLineParser sees frames as they arrive.
        val parser = HttpResponseParser()
        val events = mutableListOf<ParseEvent>()
        events += parser.feed("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n".bytes())
        events += parser.feed("3\r\nfoo\r\n".bytes())
        events += parser.feed("3\r\nbar\r\n".bytes())
        events += parser.feed("0\r\n\r\n".bytes())

        val bodyChunks = events.filterIsInstance<ParseEvent.BodyChunk>()
        assertEquals(2, bodyChunks.size)
        assertEquals("foo", bodyChunks[0].bytes.decodeToString())
        assertEquals("bar", bodyChunks[1].bytes.decodeToString())
    }

    /** Repeated field lines fold into one comma-separated value (RFC 7230 §3.2.2), not last-wins. */
    @Test
    fun repeatedHeaderLinesFoldIntoOneValue() {
        val parser = HttpResponseParser()
        val events = parser.feed(
            (
                "HTTP/1.1 302 Found\r\n" +
                    "WWW-Authenticate: Bearer realm=\"api\"\r\n" +
                    "WWW-Authenticate: Cloudflare-Access\r\n" +
                    "\r\n"
                ).bytes(),
        )

        val headers = events.filterIsInstance<ParseEvent.HeadersComplete>().single()
        assertEquals("Bearer realm=\"api\", Cloudflare-Access", headers.headers["www-authenticate"])
    }

    /**
     * Why the fold matters: the gateway check reads this header, and dropping a line makes a blocked
     * stream indistinguishable from a healthy one — no events, no error, no cause.
     */
    @Test
    fun aFoldedChallengeIsStillRecognisedWhicheverLineCarriesIt() {
        fun challengeValue(first: String, second: String): String? {
            val parser = HttpResponseParser()
            val events = parser.feed(
                (
                    "HTTP/1.1 302 Found\r\n" +
                        "WWW-Authenticate: $first\r\n" +
                        "WWW-Authenticate: $second\r\n" +
                        "\r\n"
                    ).bytes(),
            )
            return events.filterIsInstance<ParseEvent.HeadersComplete>().single()
                .headers["www-authenticate"]
        }

        assertTrue(
            AccessGatewaySignal.isGatewayChallenge(
                challengeValue("Bearer realm=\"api\"", "Cloudflare-Access"),
            ),
        )
        assertTrue(
            AccessGatewaySignal.isGatewayChallenge(
                challengeValue("Cloudflare-Access", "Bearer realm=\"api\""),
            ),
        )
    }
}
