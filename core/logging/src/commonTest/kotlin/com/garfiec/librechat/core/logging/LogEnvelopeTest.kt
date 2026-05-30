package com.garfiec.librechat.core.logging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LogEnvelopeTest {

    @Test
    fun plain_message_has_no_sentinel_and_decodes_unchanged() {
        val encoded = LogEnvelope.encode("just a message", emptyMap(), null)
        assertEquals("just a message", encoded, "no attrs/origin → message must be unchanged")
        assertFalse(encoded.startsWith(LogEnvelope.SENTINEL))

        val decoded = LogEnvelope.decode(encoded)
        assertEquals("just a message", decoded.msg)
        assertTrue(decoded.attrs.isEmpty())
        assertEquals(null, decoded.origin)
    }

    @Test
    fun attrs_and_origin_round_trip() {
        val encoded = LogEnvelope.encode(
            "request failed",
            mapOf("status" to "401", "path" to "/api/x"),
            LogOrigin.SERVER,
        )
        assertTrue(encoded.startsWith(LogEnvelope.SENTINEL))

        val decoded = LogEnvelope.decode(encoded)
        assertEquals("request failed", decoded.msg)
        assertEquals("401", decoded.attrs["status"])
        assertEquals("/api/x", decoded.attrs["path"])
        assertEquals("server", decoded.origin)
    }

    @Test
    fun message_without_sentinel_is_treated_as_plain() {
        val decoded = LogEnvelope.decode("SSE connected after 2 retries")
        assertEquals("SSE connected after 2 retries", decoded.msg)
        assertTrue(decoded.attrs.isEmpty())
    }

    @Test
    fun malformed_sentinel_payload_falls_back_to_plain() {
        val broken = LogEnvelope.SENTINEL + "{not valid json"
        val decoded = LogEnvelope.decode(broken)
        assertEquals(broken, decoded.msg, "unparseable payload must fall back to the whole string")
        assertTrue(decoded.attrs.isEmpty())
    }
}
