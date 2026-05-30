package com.garfiec.librechat.core.logging.redact

/**
 * Centralized PII scrubber applied at the sink, so redaction is enforced rather than left to
 * per-call-site discipline. Runs over every record's `msg` and every `attrs` value before it
 * reaches disk.
 *
 * Three strategies:
 *  - **Hash** identifying-but-correlatable values (tokens, emails, hosts, conversation/message IDs,
 *    bare UUIDs/ObjectIds anywhere in free text or inside URL/path segments) to a short salted
 *    FNV-1a digest, so the same value is recognizable within an export without being recoverable.
 *  - **Drop** free-form content (message/conversation text, server response bodies, JSON object
 *    literals) — content has no diagnostic value and is the highest-risk PII, so it never reaches
 *    disk at all.
 *  - **Allowlist** attr keys: only keys known to carry low-cardinality, non-identifying values pass
 *    through (still shape-scrubbed). Any *unknown* attr key is dropped by default rather than
 *    emitted raw, so a future call site that logs `fileName`/`username`/etc. cannot silently leak
 *    PII into an exportable log. Add new safe keys to [safeKeys] deliberately.
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
        // Drop inline JSON object literals carrying a quoted member, regardless of the surrounding
        // phrasing — exception messages from any serializer (not just the "JSON input:" form) can
        // echo `{"title":"…"}`-style content. Replacement has no quotes inside braces → idempotent.
        out = jsonObjectRegex.replace(out, "{<redacted>}")
        // Each replacement is shaped so it cannot re-match the same regex → redact() is idempotent.
        // bearer: drop the space after "Bearer" so `bearer\s+\S+` no longer matches.
        out = bearerRegex.replace(out) { m -> m.groupValues[1].trimEnd() + ":" + hash8(m.groupValues[2]) }
        // refresh: swap '=' for ':' so `refreshtoken=` no longer matches.
        out = refreshRegex.replace(out) { m -> "refreshtoken:" + hash8(m.groupValues[2]) }
        out = jwtRegex.replace(out) { m -> "jwt:" + hash8(m.value) }
        out = emailRegex.replace(out) { m -> "email:" + hash8(m.value) }
        // Hash bare identifiers (UUIDs, Mongo ObjectIds) wherever they appear — including inside
        // URL/request paths, which would otherwise leak conversation/message IDs verbatim. Runs
        // before urlRegex so an id inside a kept path is already hashed.
        out = uuidRegex.replace(out) { m -> "id:" + hash8(m.value) }
        out = objectIdRegex.replace(out) { m -> "id:" + hash8(m.value) }
        // url: drop the scheme/`://` so `https?://` no longer matches; keep path (diagnostically useful).
        out = urlRegex.replace(out) { m -> "url:" + hash8(m.groupValues[1] + m.groupValues[2]) + m.groupValues[3] }
        return out
    }

    fun redactAttrs(attrs: Map<String, String>): Map<String, String> {
        if (attrs.isEmpty()) return attrs
        return attrs.mapValues { (key, value) ->
            when (key.lowercase()) {
                in contentKeys -> "<redacted len=${value.length}>"
                in hashKeys -> hash8(value)
                in safeKeys -> redact(value)
                // Safe-by-default: an unrecognized key may carry free-form PII (e.g. a filename or
                // display name) with no recognizable shape, so it is dropped rather than emitted.
                else -> "<redacted len=${value.length}>"
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

        // A brace-delimited object literal containing a quoted member (e.g. `{"text":"…"}`). Requires
        // two quotes inside the braces so `{}` and brace-free text are untouched.
        private val jsonObjectRegex = Regex("\\{[^{}]*\"[^{}]*\"[^{}]*\\}")
        private val bearerRegex = Regex("(?i)(authorization\\s*[:=]\\s*bearer\\s+)(\\S+)")
        private val refreshRegex = Regex("(?i)(refreshtoken=)([^;&\\s\"]+)")
        private val jwtRegex = Regex("eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")

        // Email — also matches IP-literal/single-label hosts (user@localhost, user@10.0.0.1), which a
        // dotted-TLD-only pattern would miss. Over-matching a stray `a@b` token in a log is acceptable.
        private val emailRegex = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9][A-Za-z0-9._-]*")
        private val urlRegex = Regex("(?i)(https?://)([^/\\s:?#]+)([^\\s]*)")

        // Bare identifiers anywhere in text (incl. URL/path segments): UUID and 24-char Mongo ObjectId.
        private val uuidRegex =
            Regex("\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b")
        private val objectIdRegex = Regex("\\b[0-9a-fA-F]{24}\\b")

        // attr keys whose VALUE is free-form content → dropped entirely (length kept for debugging).
        private val contentKeys = setOf("message", "content", "text", "prompt", "title", "body", "query")

        // attr keys that are identifiers or secrets → hashed so they correlate without exposing the raw value.
        private val hashKeys = setOf(
            "conversationid", "messageid", "userid", "parentmessageid", "email",
            "authorization", "cookie", "set-cookie", "x-api-key", "api-key", "apikey",
            "token", "accesstoken", "refreshtoken", "password", "secret",
        )

        // ALLOWLIST: attr keys known to carry low-cardinality, non-identifying values. Only these pass
        // through (after a defensive shape-scrub). Every key emitted by a Diag call site must appear
        // here; an omitted key is dropped (safe-by-default), so add new keys deliberately.
        private val safeKeys = setOf(
            // build / device / platform (startup header)
            "versionname", "versioncode", "gitsha", "osname", "osversion", "devicemodel",
            // backend version + server-config snapshot flags
            "supportedbackendversion", "detectedbackendversion", "compatible",
            "registrationenabled", "emailloginenabled", "socialloginenabled", "passwordresetenabled",
            "sharedlinksenabled", "websearch", "modelspecs", "endpointcount",
            // network / SSE / HTTP failure attribution
            "status", "method", "path", "endpoint", "attempt", "timeoutsec", "failedfields",
            // auth / lifecycle / breadcrumb / watchdog / crash
            "event", "reason", "screen", "blockedms", "throwable",
        )
    }
}
