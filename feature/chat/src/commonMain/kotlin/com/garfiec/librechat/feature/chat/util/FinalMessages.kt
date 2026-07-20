package com.garfiec.librechat.feature.chat.util

import com.garfiec.librechat.core.model.ContentType
import com.garfiec.librechat.core.model.Message
import com.garfiec.librechat.core.model.StreamEvent
import com.garfiec.librechat.core.model.content.MessageContentPart

/**
 * The assistant reply carried by a Final event. The server may deliver it in either
 * `responseMessage` (current) or the legacy top-level `message` field, so the fallback
 * lives in one place instead of being hand-copied at every call site.
 */
fun StreamEvent.Final.resolvedResponseMessage(): Message? = responseMessage ?: message

/**
 * The completed turn's messages (request first, then response), as echoed by the Final
 * event. Either may be absent on a loose payload (`core/model` schema is intentionally
 * non-exhaustive); callers that must *persist* the turn should backfill a missing request
 * message from in-memory state so the user's own message is never dropped.
 */
fun StreamEvent.Final.finalMessages(): List<Message> =
    listOfNotNull(requestMessage, resolvedResponseMessage())

/**
 * Faithful port of the server's `parseTextParts`
 * (upstream/packages/data-provider/src/parsers.ts) as the abort path calls it — with
 * `skipReasoning = false`, so THINK parts are folded in alongside TEXT parts (stopping a
 * reasoning model mid-think persists the reasoning as the row's `text`). The separator rule
 * is the server's exactly: a single `' '` between two chunks iff the running result is
 * non-empty, the incoming value is non-empty, the result's last char is not `' '`, and the
 * incoming's first char is not `' '` — a literal-space comparison, NOT isWhitespace(), so a
 * chunk ending in `\n` still gets the space the server inserts.
 *
 * MIRRORED SERVER LOGIC — verify against upstream on version bumps (/sync-upstream).
 */
fun parseTextParts(parts: List<MessageContentPart>): String {
    val result = StringBuilder()
    for (part in parts) {
        val value = when (part.type) {
            ContentType.TEXT -> part.text
            ContentType.THINK -> part.think
            else -> null
        } ?: continue
        if (result.isNotEmpty() && value.isNotEmpty() && result.last() != ' ' && value.first() != ' ') {
            result.append(' ')
        }
        result.append(value)
    }
    return result.toString()
}

/**
 * Whether the server actually persisted this aborted turn's response — the exact client-side
 * mirror of the abort route's save gate (upstream/api/server/routes/agents/index.js), which
 * requires ALL of: a user message id, a real `responseMessageId`, and persistable content.
 * Evaluated from the same values the server emitted in the frame:
 *
 * - `earlyAbort` → the server saved nothing at all (not even the user message).
 * - `requestMessage == null` → `jobData.userMessage` was absent, failing the gate.
 * - empty `content` → `hasPersistableAbortContent` failed. The frame's `content` is the
 *   already-server-filtered persistable parts, so no filtering is re-implemented here.
 * - a messageId of `"${parentMessageId}_"` is the frame's synthesized fallback for a null
 *   `jobData.responseMessageId` — which also fails the gate, even with content present.
 *
 * Caching a turn the server didn't persist would strand rows a later fetch never returns
 * (`getMessages` upserts and never deletes), so callers drop what this returns false for.
 *
 * MIRRORED SERVER LOGIC — verify against upstream on version bumps (/sync-upstream).
 */
fun StreamEvent.Final.abortPersistedServerSide(): Boolean {
    if (earlyAbort) return false
    if (requestMessage == null) return false
    val response = resolvedResponseMessage() ?: return false
    if (response.content.isNullOrEmpty()) return false
    return response.messageId != "${response.parentMessageId}_"
}

/**
 * Makes an aborted frame agree with what the server persisted, in one place, before anything
 * renders or caches it:
 *
 * - **Persisted** response: rebuild the missing `text` via [parseTextParts] — the server
 *   computes the same string for its saved row and simply omits it from the frame, so the
 *   backfilled row matches what a later fetch returns instead of drifting from it.
 * - **NOT persisted** ([abortPersistedServerSide] false): drop the response from BOTH slots.
 *   Merging it would put a message on screen that exists nowhere server-side — a phantom leaf
 *   the next send would use as its `parentMessageId`, which the server has never heard of.
 *   The request message stays: on a non-early abort the server did persist the user turn.
 *
 * The skeletal `requestMessage` needs no handling here — the monotonic `mergedOver` merge
 * gap-fills it over the richer in-memory optimistic message.
 */
fun StreamEvent.Final.applyAbortContract(): StreamEvent.Final {
    if (!aborted) return this
    if (!abortPersistedServerSide()) return copy(responseMessage = null, message = null)
    val response = resolvedResponseMessage() ?: return this
    if (response.text.isNotBlank()) return this
    val rebuilt = parseTextParts(response.content.orEmpty())
    if (rebuilt.isBlank()) return this
    val withText = response.copy(text = rebuilt)
    // The response arrives in whichever slot the backend used; put it back in the same one.
    return if (responseMessage != null) {
        copy(responseMessage = withText)
    } else {
        copy(message = withText)
    }
}
