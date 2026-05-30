package com.garfiec.librechat.core.logging.redact

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LogRedactorTest {

    private val redactor = LogRedactor(salt = "test-salt")

    @Test
    fun bearer_token_is_redacted_and_not_present() {
        val input = "Authorization: Bearer eyJhbGciOiJIUzI1NibXVuY2hraW4 done"
        val out = redactor.redact(input)
        assertFalse(out.contains("eyJhbGciOiJIUzI1NibXVuY2hraW4"), "raw token leaked: $out")
        assertTrue(out.contains("Bearer:"), "expected redacted bearer marker: $out")
    }

    @Test
    fun raw_jwt_in_message_is_redacted() {
        val jwt = "eyJhbGc.eyJzdWIiOiIxMjM0NTY3ODkwIn0.SflKxwRJSMeKKF2QT4"
        val out = redactor.redact("token=$jwt end")
        assertFalse(out.contains(jwt), "raw jwt leaked: $out")
        assertTrue(out.contains("jwt:"), out)
    }

    @Test
    fun refresh_token_redacted_preserving_delimiters() {
        val out = redactor.redact("refreshToken=abc123secret; path=/")
        assertFalse(out.contains("abc123secret"), out)
        assertTrue(out.contains("path=/"), "surrounding content should remain: $out")
    }

    @Test
    fun email_is_redacted_in_free_text() {
        val out = redactor.redact("login failed for alice@example.com after retry")
        assertFalse(out.contains("alice@example.com"), out)
        assertTrue(out.contains("email:"), out)
    }

    @Test
    fun server_url_host_is_redacted_but_path_kept() {
        val out = redactor.redact("GET https://chat.example.com/api/messages/123 failed")
        assertFalse(out.contains("chat.example.com"), "host leaked: $out")
        assertTrue(out.contains("/api/messages/123"), "path should be kept: $out")
    }

    @Test
    fun content_attr_keys_are_dropped_by_length() {
        val out = redactor.redactAttrs(mapOf("message" to "hello secret world", "endpoint" to "/api/x"))
        assertEquals("<redacted len=18>", out["message"])
        assertEquals("/api/x", out["endpoint"], "benign keys pass through")
    }

    @Test
    fun id_attr_keys_are_hashed_not_dropped() {
        val out = redactor.redactAttrs(mapOf("conversationId" to "conv_abc"))
        assertFalse(out["conversationId"]!!.contains("conv_abc"), out.toString())
        assertTrue(out["conversationId"]!!.isNotBlank())
    }

    @Test
    fun secret_attr_keys_are_hashed() {
        val out = redactor.redactAttrs(mapOf("authorization" to "Bearer xyz", "x-api-key" to "k-12345"))
        assertFalse(out["authorization"]!!.contains("xyz"), out.toString())
        assertFalse(out["x-api-key"]!!.contains("12345"), out.toString())
    }

    @Test
    fun embedded_json_response_body_is_stripped() {
        // Serialization errors echo the offending payload, which can carry message/conversation
        // content. It must not reach disk.
        val out = redactor.redact(
            "Illegal input at path: \$[0]\nJSON input: [{\"text\":\"secret user message\"}]",
        )
        assertFalse(out.contains("secret user message"), "response body content leaked: $out")
        assertTrue(out.contains("JSON input: <redacted>"), out)
    }

    @Test
    fun benign_message_passes_through_unchanged() {
        val msg = "Loading config for tab 3 (count=12)"
        assertEquals(msg, redactor.redact(msg), "false positive on benign text")
    }

    @Test
    fun request_path_ids_are_hashed_but_route_kept() {
        // HTTP failure logs the request path under the "path" attr; id segments must not leak.
        val convId = "123e4567-e89b-12d3-a456-426614174000"
        val out = redactor.redactAttrs(mapOf("path" to "/api/messages/$convId"))
        assertFalse(out["path"]!!.contains(convId), "raw conversation id leaked in path: ${out["path"]}")
        assertTrue(out["path"]!!.startsWith("/api/messages/id:"), "route shape should survive: ${out["path"]}")
    }

    @Test
    fun mongo_object_id_in_free_text_is_hashed() {
        val out = redactor.redact("loaded conversation 6831ab9c2d4e5f0011223344 ok")
        assertFalse(out.contains("6831ab9c2d4e5f0011223344"), "raw object id leaked: $out")
        assertTrue(out.contains("id:"), out)
    }

    @Test
    fun unknown_attr_key_is_dropped_by_default() {
        // Safe-by-default: a key not on the allowlist may carry free-form PII (filename, name…).
        val out = redactor.redactAttrs(mapOf("fileName" to "JohnSmith_tax_2025.pdf"))
        assertFalse(out["fileName"]!!.contains("JohnSmith"), "unknown key leaked raw PII: ${out["fileName"]}")
        assertEquals("<redacted len=22>", out["fileName"])
    }

    @Test
    fun email_without_dotted_tld_is_redacted() {
        val out = redactor.redact("login failed for admin@localhost retry")
        assertFalse(out.contains("admin@localhost"), "single-label-host email leaked: $out")
        assertTrue(out.contains("email:"), out)
    }

    @Test
    fun embedded_json_object_without_marker_is_dropped() {
        // A serializer error phrased without "JSON input:" still echoes object literals with content.
        val out = redactor.redact("Field error near {\"text\":\"secret note\"} at offset 12")
        assertFalse(out.contains("secret note"), "json object content leaked: $out")
        assertTrue(out.contains("{<redacted>}"), out)
    }

    @Test
    fun hash_is_stable_for_same_value() {
        val a = redactor.redact("user alice@example.com")
        val b = redactor.redact("user alice@example.com")
        assertEquals(a, b, "same input must hash to same output for correlation")
    }

    @Test
    fun redaction_is_idempotent() {
        val once = redactor.redact("Authorization: Bearer eyJabc.def.ghi to https://h.example.com/p and a@b.co refreshToken=zzz")
        val twice = redactor.redact(once)
        assertEquals(once, twice, "double-redaction must be stable: '$once' vs '$twice'")
    }

    @Test
    fun multiple_secrets_in_one_line_all_redacted() {
        val out = redactor.redact("Authorization: Bearer eyJsecrettoken and email bob@corp.io")
        assertFalse(out.contains("eyJsecrettoken"), out)
        assertFalse(out.contains("bob@corp.io"), out)
    }
}
