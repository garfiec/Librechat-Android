package com.garfiec.librechat.core.logging.redact

/**
 * Centralized PII scrubber applied at the sink, so redaction is enforced rather than left to
 * per-call-site discipline. Runs over every record's `msg` and every `attrs` value before it
 * reaches disk.
 *
 * Two strategies:
 *  - **Hash** identifying-but-correlatable values (tokens, emails, hosts, conversation/message IDs)
 *    to a short salted FNV-1a digest, so the same value is recognizable within an export without
 *    being recoverable.
 *  - **Drop** free-form content (message/conversation text) by attr-key denylist — content has no
 *    diagnostic value and is the highest-risk PII, so it never reaches disk at all.
 *
 * Redaction is idempotent: re-running it over already-redacted output is stable.
 */
class LogRedactor(private val salt: String = DEFAULT_SALT) {

    fun redact(input: String): String {
        if (input.isEmpty()) return input
        var out = input
        // Strip server response bodies that serialization errors echo into their message
        // ("...JSON input: <body>"). The body can carry message/conversation CONTENT, which must
        // never reach disk. Drops everything from the marker to end-of-line. Idempotent: the
        // replacement contains no further "JSON input:" marker.
        out = jsonInputRegex.replace(out, "JSON input: <redacted>")
        // Each replacement is shaped so it cannot re-match the same regex → redact() is idempotent.
        // bearer: drop the space after "Bearer" so `bearer\s+\S+` no longer matches.
        out = bearerRegex.replace(out) { m -> m.groupValues[1].trimEnd() + ":" + hash8(m.groupValues[2]) }
        // refresh: swap '=' for ':' so `refreshtoken=` no longer matches.
        out = refreshRegex.replace(out) { m -> "refreshtoken:" + hash8(m.groupValues[2]) }
        out = jwtRegex.replace(out) { m -> "jwt:" + hash8(m.value) }
        out = emailRegex.replace(out) { m -> "email:" + hash8(m.value) }
        // url: drop the scheme/`://` so `https?://` no longer matches; keep path (diagnostically useful).
        out = urlRegex.replace(out) { m -> "url:" + hash8(m.groupValues[1] + m.groupValues[2]) + m.groupValues[3] }
        return out
    }

    fun redactAttrs(attrs: Map<String, String>): Map<String, String> {
        if (attrs.isEmpty()) return attrs
        return attrs.mapValues { (key, value) ->
            when (key.lowercase()) {
                in contentKeys -> "<redacted len=${value.length}>"
                in idHashKeys -> hash8(value)
                in secretKeys -> hash8(value)
                else -> redact(value)
            }
        }
    }

    /** 64-bit FNV-1a → first 8 hex chars. Dependency-free; for correlation, not cryptographic secrecy. */
    private fun hash8(value: String): String {
        var hash = FNV_OFFSET_BASIS
        for (byte in (salt + value).encodeToByteArray()) {
            hash = hash xor (byte.toLong() and 0xff)
            hash *= FNV_PRIME
        }
        return hash.toULong().toString(16).padStart(8, '0').take(8)
    }

    companion object {
        const val DEFAULT_SALT: String = "librechat-diag"

        private const val FNV_OFFSET_BASIS: Long = -3750763034362895579L // 14695981039346656037 as Long
        private const val FNV_PRIME: Long = 1099511628211L

        // kotlinx.serialization echoes the offending payload after "JSON input:" — strip to EOL.
        private val jsonInputRegex = Regex("JSON input:.*")
        private val bearerRegex = Regex("(?i)(authorization\\s*[:=]\\s*bearer\\s+)(\\S+)")
        private val refreshRegex = Regex("(?i)(refreshtoken=)([^;&\\s\"]+)")
        private val jwtRegex = Regex("eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")
        private val emailRegex = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
        private val urlRegex = Regex("(?i)(https?://)([^/\\s:?#]+)([^\\s]*)")

        // attr keys whose VALUE is free-form content → dropped entirely (length kept for debugging).
        private val contentKeys = setOf("message", "content", "text", "prompt", "title", "body", "query")

        // attr keys that are identifiers → hashed so they correlate without exposing the raw id.
        private val idHashKeys = setOf("conversationid", "messageid", "userid", "parentmessageid", "email")

        // attr keys that are secrets → hashed (never kept verbatim).
        private val secretKeys = setOf(
            "authorization", "cookie", "set-cookie", "x-api-key", "api-key", "apikey",
            "token", "accesstoken", "refreshtoken", "password", "secret",
        )
    }
}
