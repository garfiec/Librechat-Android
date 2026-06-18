package com.garfiec.librechat.feature.chat.util

import com.garfiec.librechat.core.model.Message
import com.garfiec.librechat.core.model.StreamEvent

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
