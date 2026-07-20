package com.garfiec.librechat.feature.chat.util

import com.garfiec.librechat.core.model.ContentType
import com.garfiec.librechat.core.model.Conversation
import com.garfiec.librechat.core.model.Message
import com.garfiec.librechat.core.model.StreamEvent
import com.garfiec.librechat.core.model.content.MessageContentPart

/**
 * Aborted `final` frames shaped EXACTLY as the server emits them
 * (upstream/packages/api/src/stream/GenerationJobManager.ts, abortJob):
 *
 * - `conversation` is a stub — `{ conversationId }` only — with the hardcoded 'New Chat' title.
 * - `requestMessage` is skeletal: id/parent/conversationId/text/isCreatedByUser, no
 *   files/attachments/sender/createdAt.
 * - `responseMessage` carries the server-filtered `content` parts and NO `text`;
 *   `unfinished = true`, `error = false`. Its id falls back to `"${userMessageId}_"` when the
 *   job had no responseMessageId (which also means the server did NOT persist it).
 * - an `earlyAbort` frame has `conversation = null` and `responseMessage = null`.
 *
 * The prior test round hand-wrote frames with `text` populated and `content` null — the exact
 * inverse of the wire shape — so every assertion short-circuited past the normalize/cache
 * logic. Build test frames from here instead.
 */
object AbortFrameFixtures {

    const val CONVERSATION_ID = "conv-1"
    const val USER_MESSAGE_ID = "u1"
    const val RESPONSE_MESSAGE_ID = "a1"

    fun skeletalRequest(
        userMessageId: String = USER_MESSAGE_ID,
        text: String = "hi",
    ) = Message(
        messageId = userMessageId,
        conversationId = CONVERSATION_ID,
        parentMessageId = null,
        text = text,
        isCreatedByUser = true,
    )

    fun abortedResponse(
        responseId: String = RESPONSE_MESSAGE_ID,
        userMessageId: String = USER_MESSAGE_ID,
        content: List<MessageContentPart>? = listOf(
            MessageContentPart(type = ContentType.TEXT, text = "partial answer"),
        ),
    ) = Message(
        messageId = responseId,
        conversationId = CONVERSATION_ID,
        parentMessageId = userMessageId,
        text = "", // the frame never carries text; the server computes it only for its own row
        content = content,
        sender = "AI",
        unfinished = true,
        error = false,
        isCreatedByUser = false,
    )

    /** A stopped turn the server DID persist: real response id, non-empty filtered content. */
    fun persistedAbortFrame(
        content: List<MessageContentPart> = listOf(
            MessageContentPart(type = ContentType.TEXT, text = "partial answer"),
        ),
    ) = StreamEvent.Final(
        conversation = Conversation(conversationId = CONVERSATION_ID, title = "New Chat"),
        requestMessage = skeletalRequest(),
        responseMessage = abortedResponse(content = content),
        aborted = true,
    )

    /**
     * Content survived the stop but the job never assigned a responseMessageId, so the frame's
     * id is the synthesized `"${userMessageId}_"` — and the server's save gate skipped the row.
     */
    fun synthesizedIdAbortFrame() = StreamEvent.Final(
        conversation = Conversation(conversationId = CONVERSATION_ID, title = "New Chat"),
        requestMessage = skeletalRequest(),
        responseMessage = abortedResponse(responseId = "${USER_MESSAGE_ID}_"),
        aborted = true,
    )

    /** Stopped with nothing persistable: the server sends the response but saved nothing. */
    fun contentlessAbortFrame() = StreamEvent.Final(
        conversation = Conversation(conversationId = CONVERSATION_ID, title = "New Chat"),
        requestMessage = skeletalRequest(),
        responseMessage = abortedResponse(content = emptyList()),
        aborted = true,
    )

    /** Stop before the `created` milestone: the server saved NOTHING, not even the user message. */
    fun earlyAbortFrame(userText: String = "hi") = StreamEvent.Final(
        conversation = null,
        requestMessage = skeletalRequest(text = userText),
        responseMessage = null,
        aborted = true,
        earlyAbort = true,
    )
}
