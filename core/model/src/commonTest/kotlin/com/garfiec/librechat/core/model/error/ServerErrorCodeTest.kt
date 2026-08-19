package com.garfiec.librechat.core.model.error

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ServerErrorCodeTest {

    @Test
    fun reads_the_code_key_used_by_the_generation_routes() {
        val body = """{"code":"RUN_STILL_ACTIVE","message":"Run is still active"}"""
        assertEquals(ServerErrorCode.RUN_STILL_ACTIVE, ServerErrorCode.from(body))
    }

    @Test
    fun reads_the_error_key_used_by_the_mcp_controller() {
        // The exact body `handleMCPError` writes (api/server/controllers/mcp.js): the code lands
        // under `error`, not `code`, so a reader that only knows `code` makes the re-entry prompt
        // unreachable while nothing fails to decode.
        val body = """{"error":"MCP_OAUTH_SECRET_REENTRY_REQUIRED","message":"Client secret required"}"""
        assertEquals(ServerErrorCode.OAUTH_SECRET_REENTRY_REQUIRED, ServerErrorCode.from(body))
    }

    @Test
    fun mcp_reentry_constant_matches_the_wire_value_not_the_typescript_member_name() {
        // packages/api/src/mcp/errors.ts:
        //   OAUTH_SECRET_REENTRY_REQUIRED: 'MCP_OAUTH_SECRET_REENTRY_REQUIRED'
        assertEquals("MCP_OAUTH_SECRET_REENTRY_REQUIRED", ServerErrorCode.OAUTH_SECRET_REENTRY_REQUIRED)
    }

    @Test
    fun code_wins_over_error_when_both_are_present() {
        val body = """{"code":"SERVER_NOT_READY","error":"something went wrong"}"""
        assertEquals(ServerErrorCode.SERVER_NOT_READY, ServerErrorCode.from(body))
    }

    @Test
    fun the_preliminary_parent_409_reads_as_uncoded_on_the_generation_routes() {
        // The exact body `rejectPreliminaryParentMessageId` writes
        // (api/server/controllers/agents/request.js). It is the ONLY 409 on the send route worth
        // retrying, and the absence of a `code` is what says so — so reading it through `from`,
        // whose MCP fallback hands back the English sentence, makes the retry unreachable while
        // nothing fails to decode.
        val sentence = "Cannot submit a follow-up while the selected parent response is still " +
            "being saved. Please wait and try again."
        val body = """{"error":"$sentence","generationProtocolVersion":2}"""
        assertNull(ServerErrorCode.generationCodeOf(body))
        // And the reason the generic reader cannot be used here.
        assertEquals(sentence, ServerErrorCode.from(body))
    }

    @Test
    fun the_coded_409s_beside_it_still_read_as_coded() {
        // Each of these must NOT be retried, so each has to come back non-null from the same
        // reader that returns null for the body above.
        assertEquals("RUN_REPLACED", ServerErrorCode.generationCodeOf("""{"code":"RUN_REPLACED"}"""))
        assertEquals(
            "GENERATION_PREDECESSOR_MISMATCH",
            ServerErrorCode.generationCodeOf(
                """{"status":"predecessor_mismatch","code":"GENERATION_PREDECESSOR_MISMATCH",""" +
                    """"error":"A newer generation became current."}""",
            ),
        )
        assertEquals(
            "resource_recovery_required",
            ServerErrorCode.generationCodeOf(
                """{"code":"resource_recovery_required","error":"Attached resources must be restored before retrying."}""",
            ),
        )
    }

    @Test
    fun the_generation_reader_degrades_the_same_way_from_does() {
        assertNull(ServerErrorCode.generationCodeOf(null))
        assertNull(ServerErrorCode.generationCodeOf(""))
        assertNull(ServerErrorCode.generationCodeOf("not json at all"))
        assertNull(ServerErrorCode.generationCodeOf("""["RUN_STILL_ACTIVE"]"""))
        assertNull(ServerErrorCode.generationCodeOf("""{"code":{"nested":"value"}}"""))
    }

    @Test
    fun degrades_on_bodies_that_carry_no_string_code() {
        assertNull(ServerErrorCode.from(null))
        assertNull(ServerErrorCode.from(""))
        assertNull(ServerErrorCode.from("not json at all"))
        assertNull(ServerErrorCode.from("""["RUN_STILL_ACTIVE"]"""))
        assertNull(ServerErrorCode.from("""{"message":"plain failure"}"""))
        // A non-primitive value must degrade rather than throw.
        assertNull(ServerErrorCode.from("""{"code":{"nested":"value"},"error":{"nested":"value"}}"""))
    }
}
