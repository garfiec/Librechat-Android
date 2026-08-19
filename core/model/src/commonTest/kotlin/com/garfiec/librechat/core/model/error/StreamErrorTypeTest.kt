package com.garfiec.librechat.core.model.error

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StreamErrorTypeTest {

    /**
     * The exact shape a v0.8.8-rc1 model-not-found failure arrives in: not a typed payload at
     * all, but provider JSON with a LangChain troubleshooting URL buried in the prose.
     */
    private val modelNotFoundInBand = """
        {"error":{"message":"404 {\"error\":{\"message\":\"The model `gpt-5-nonexistent` does not
        exist or you do not have access to it.\",\"type\":\"invalid_request_error\"}}
        Troubleshooting URL: https://js.langchain.com/docs/troubleshooting/errors/MODEL_NOT_FOUND/"}}
    """.trimIndent()

    @Test
    fun the_in_band_error_part_classifies_the_same_as_the_stream_end_reason() {
        // The failure reaches the user twice over: as the reason the run ended, and as an `error`
        // content part on the persisted assistant message. rc1 persists that message with
        // `error: false` and no text, so on a reopened conversation the part is the ONLY record
        // of it — and it was rendering the raw payload above while the snackbar showed the
        // actionable sentence for the very same error.
        assertEquals(StreamErrorType.MODEL_NOT_FOUND, StreamErrorType.parse(modelNotFoundInBand))
        assertEquals(StreamErrorType.MODEL_NOT_FOUND.marker, StreamErrorType.markerOrText(modelNotFoundInBand))
    }

    @Test
    fun an_unrecognized_payload_keeps_the_servers_own_text() {
        // The contract, not a fallback: a newer server's code must reach the user as the server's
        // sentence, never as a bare identifier or an empty string.
        val future = """{"type":"some_code_this_build_has_never_heard_of","message":"Upstream said no"}"""
        assertNull(StreamErrorType.parse(future))
        assertEquals(future, StreamErrorType.markerOrText(future))

        val prose = "The provider is temporarily unavailable. Try again shortly."
        assertEquals(prose, StreamErrorType.markerOrText(prose))
    }

    @Test
    fun a_typed_payload_still_classifies_through_the_shared_entry_point() {
        val typed = """{"type":"resource_recovery_required","message":"Files could not be restored"}"""
        assertEquals(
            StreamErrorType.RESOURCE_RECOVERY_REQUIRED.marker,
            StreamErrorType.markerOrText(typed),
        )
    }

    @Test
    fun a_blank_error_part_stays_blank() {
        // `part.error ?: part.text.orEmpty()` can legitimately be empty; classifying it must not
        // invent a marker, and the render site draws nothing rather than an empty localized card.
        assertNull(StreamErrorType.parse(""))
        assertEquals("", StreamErrorType.markerOrText(""))
    }

    @Test
    fun the_url_pattern_does_not_fire_on_a_neighbouring_langchain_error_code() {
        // MODEL_NOT_FOUND is matched by URL because it is not one of upstream's ErrorTypes, which
        // makes over-matching the risk: every LangChain troubleshooting link shares the prefix.
        val other = "Troubleshooting URL: https://js.langchain.com/docs/troubleshooting/errors/INVALID_TOOL_RESULTS/"
        assertNull(StreamErrorType.parse(other))
    }

    @Test
    fun markers_round_trip_through_the_wire_value() {
        // `localizedStreamError` resolves a marker by matching the enum on `wire`, so a marker
        // this side mints has to be resolvable by that lookup for every entry.
        StreamErrorType.entries.forEach { type ->
            assertEquals(type.wire, type.marker.removePrefix(StreamErrorType.MARKER_PREFIX))
        }
    }
}
