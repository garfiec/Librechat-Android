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
