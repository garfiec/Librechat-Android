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
     *
     * The wire value carries the `MCP_` prefix that upstream's TypeScript member name
     * (`MCPErrorCodes.OAUTH_SECRET_REENTRY_REQUIRED`) drops — see `packages/api/src/mcp/errors.ts`.
     */
    const val OAUTH_SECRET_REENTRY_REQUIRED = "MCP_OAUTH_SECRET_REENTRY_REQUIRED"

    private val parser = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Reads the machine-readable code off a raw error-response body, or null when the body is
     * absent, not a JSON object, or carries no string code.
     *
     * Two keys, because the backend has two conventions: the generation routes answer
     * `{ "code": ... }`, while the MCP controller's `handleMCPError` answers
     * `{ "error": <code>, "message": ... }` (`api/server/controllers/mcp.js`). `code` wins when
     * both are present, so a body that puts a human sentence in `error` next to a real `code`
     * still reads correctly.
     *
     * Safe-casts rather than using the `.jsonPrimitive` extension, which throws on an object or
     * array value — a server that answers `{"error":{...}}` must degrade, not crash the caller.
     */
    fun from(body: String?): String? {
        val obj = objectOf(body) ?: return null
        return (obj["code"] as? JsonPrimitive)?.contentOrNull
            ?: (obj["error"] as? JsonPrimitive)?.contentOrNull
    }

    /**
     * The `code` on a generation-route error body, WITHOUT [from]'s `error` fallback.
     *
     * On those routes the absence of a code is itself a discriminator, so the fallback cannot be
     * used there. `rejectPreliminaryParentMessageId` (`api/server/controllers/agents/request.js`)
     * answers a 409 carrying only an English sentence under `error` — the one 409 on the send route
     * that is a transient race worth retrying — while every coded 409 beside it (`RUN_REPLACED`,
     * `RESOURCE_RECOVERY_REQUIRED`, `GENERATION_PREDECESSOR_MISMATCH`, `RECOVERY_PAYLOAD_MISMATCH`)
     * must not be retried. Read through [from], that sentence comes back as a code and the
     * uncoded case becomes unreachable: nothing fails to decode, and the retry silently never fires.
     *
     * The `error` fallback stays on [from] for the MCP controller, whose bodies put the code there.
     */
    fun generationCodeOf(body: String?): String? =
        (objectOf(body)?.get("code") as? JsonPrimitive)?.contentOrNull

    private fun objectOf(body: String?): JsonObject? {
        if (body.isNullOrBlank()) return null
        val element = runCatching { parser.parseToJsonElement(body) }.getOrNull() ?: return null
        return element as? JsonObject
    }
}
