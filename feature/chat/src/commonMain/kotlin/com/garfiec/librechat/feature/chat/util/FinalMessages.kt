package com.garfiec.librechat.feature.chat.util

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
 * Adapts an aborted final event for display: when the response's parent — the turn's user
 * message — is already in [messages], drop the frame's `requestMessage` so
 * `finalizeChatDisplay` backfills from the richer in-memory copy instead. An aborted frame
 * carries a skeletal request (`messageId`, `parentMessageId`, `conversationId`, `text`,
 * `quotes`, `isCreatedByUser` — no files, attachments, sender, or createdAt), and
 * `mergeFinalMessagesInMemory` replaces wholesale by id, so without this the optimistic
 * message's attachments would be stripped on screen *and* in the cached turn. When the parent
 * is NOT in memory (a backend that didn't adopt the client-minted id), the skeletal copy is
 * still better than losing the request entirely, so it's kept.
 */
fun StreamEvent.Final.preferInMemoryRequest(messages: List<Message>): StreamEvent.Final {
    val parentId = resolvedResponseMessage()?.parentMessageId ?: return this
    if (requestMessage == null) return this
    return if (messages.any { it.messageId == parentId }) copy(requestMessage = null) else this
}

/**
 * Prepares an aborted final for the normal completion path.
 *
 * An aborted frame is deliberately poorer than a completed one, and we persist it locally where
 * the web client does not — so it gets normalized once, here, before anything renders or caches
 * it: the skeletal request is swapped for the in-memory copy (see [preferInMemoryRequest]), and
 * the missing `text` is rebuilt from the content parts. The server computes that same `text`
 * with `parseTextParts` when it saves its own row and simply omits it from the frame, so
 * backfilling makes the cached row match what a later fetch will return instead of drifting
 * from it.
 */
fun StreamEvent.Final.normalizeAbortedFrame(messages: List<Message>): StreamEvent.Final {
    val normalized = preferInMemoryRequest(messages)
    val response = normalized.resolvedResponseMessage() ?: return normalized
    if (response.text.isNotBlank()) return normalized
    val rebuilt = response.content?.textFromParts().orEmpty()
    if (rebuilt.isBlank()) return normalized
    val withText = response.copy(text = rebuilt)
    // The response arrives in whichever slot the backend used; put it back in the same one.
    return if (normalized.responseMessage != null) {
        normalized.copy(responseMessage = withText)
    } else {
        normalized.copy(message = withText)
    }
}

/**
 * Concatenation of the text parts, mirroring the server's `parseTextParts` — including its rule
 * of inserting a space between two parts that would otherwise run together.
 */
private fun List<MessageContentPart>.textFromParts(): String {
    val result = StringBuilder()
    for (part in this) {
        val value = part.text ?: continue
        if (value.isEmpty()) continue
        if (result.isNotEmpty() && !result.last().isWhitespace() && !value.first().isWhitespace()) {
            result.append(' ')
        }
        result.append(value)
    }
    return result.toString()
}

/**
 * Whether the server actually persisted this aborted turn — i.e. whether caching it locally
 * would agree with what a later fetch returns.
 *
 * The server saves the stopped response only when it has persistable content, applying
 * `hasPersistableAbortContent` (non-blank text/think, no OAuth prompt) to the same already-
 * filtered parts it puts in the frame. So an empty `content` here means the server saved
 * nothing at all, even though it still sent a non-null `responseMessage`. Caching that would
 * leave a blank assistant bubble that no later fetch removes — `getMessages` upserts and never
 * deletes rows the server didn't return, so only an explicit pull-to-refresh would clear it.
 * An `earlyAbort` is the same story one step earlier: nothing was saved, and there is no
 * conversation to reconcile against.
 */
fun StreamEvent.Final.abortWasPersistedServerSide(): Boolean {
    if (earlyAbort) return false
    val response = resolvedResponseMessage() ?: return false
    return !response.content.isNullOrEmpty()
}
