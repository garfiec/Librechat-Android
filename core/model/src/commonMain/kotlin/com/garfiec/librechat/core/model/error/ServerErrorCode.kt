package com.garfiec.librechat.core.model.error

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Machine-readable `code` values the backend puts on an error response body.
 *
 * Upstream moved from bare English messages to typed codes across the generation routes
 * (`api/server/routes/agents/index.js`, the `api/server/controllers/agents` controllers) and the MCP
 * controller (`packages/api/src/mcp/errors.ts`). A code is the only part of an error body that
 * is safe to branch on — the accompanying `message` is localized, reworded between releases and
 * sometimes replaced wholesale by a gateway.
 *
 * These are string constants rather than an enum on purpose: an unknown code must degrade to the
 * generic path, and an enum would force every call site to carry an `UNKNOWN` case that means
 * exactly what a null already means.
 */
object ServerErrorCode {
    /**
     * 409 from `POST /api/agents/chat/abort` — the run acknowledged the abort but has not yet
     * reached a stoppable point. Carries `Retry-After: 1`; the abort is expected to succeed on a
     * retry, so surfacing it as a stop failure is wrong.
     */
    const val RUN_STILL_ACTIVE = "RUN_STILL_ACTIVE"

    /**
     * 400 from the abort route when a target field is present but zero-length or over 512 chars.
     * The client should never provoke this — see `ChatAbortRequest`, which is what stopped mobile
     * sending an empty `abortKey`.
     */
    const val INVALID_ABORT_TARGET = "INVALID_ABORT_TARGET"

    /**
     * 503 from `GET /api/agents/chat/status/:conversationId`, with `Retry-After: 1`.
     *
     * A *transient race*, not a dead run: the route re-reads the job up to three times to verify
     * the resume snapshot belongs to the same generation epoch, and answers this while that has
     * not settled or while `terminalPersistencePending` is true. Giving up here abandons a run
     * that is still alive.
     */
    const val SERVER_NOT_READY = "SERVER_NOT_READY"

    /**
     * 400 from the MCP server create/update routes once the stored OAuth client secret has been
     * bound to the authorization/token endpoint it was issued for. Changing either endpoint
     * invalidates the secret, and the write keeps failing until the user re-enters it — so this
     * is a prompt-for-input outcome, not a retry-or-report one.
     */
    const val OAUTH_SECRET_REENTRY_REQUIRED = "OAUTH_SECRET_REENTRY_REQUIRED"

    private val parser = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Reads the `code` field off a raw error-response body, or null when the body is absent, not
     * a JSON object, or carries no string `code`.
     *
     * Safe-casts rather than using the `.jsonPrimitive` extension, which throws on an object or
     * array value — a server that answers `{"code":{...}}` must degrade, not crash the caller.
     */
    fun from(body: String?): String? {
        if (body.isNullOrBlank()) return null
        val element = runCatching { parser.parseToJsonElement(body) }.getOrNull() ?: return null
        val obj = element as? JsonObject ?: return null
        return (obj["code"] as? JsonPrimitive)?.contentOrNull
    }
}
