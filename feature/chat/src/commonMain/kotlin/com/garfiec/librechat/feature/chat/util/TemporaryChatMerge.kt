package com.garfiec.librechat.feature.chat.util

import com.garfiec.librechat.core.model.Message

/**
 * Merges the final request/response [Message]s from a temporary chat's SSE Final
 * event into the existing in-memory message list — WITHOUT any DB round-trip. A
 * message with an id already present (the optimistic user message / streaming
 * placeholder) is replaced in place; genuinely new messages are appended, keeping
 * insertion order stable.
 *
 * Temporary chats are never persisted to Room (a data-at-rest leak would
 * otherwise survive even though the conversation is hidden from history), so the
 * normal `loadConversation` read-through is bypassed and the display is driven from
 * this pure merge instead.
 */
fun mergeFinalMessagesInMemory(
    existing: List<Message>,
    finalMessages: List<Message>,
): List<Message> {
    val byId = existing.associateBy { it.messageId }.toMutableMap()
    val ordered = existing.map { it.messageId }.toMutableList()
    for (msg in finalMessages) {
        if (!byId.containsKey(msg.messageId)) ordered.add(msg.messageId)
        byId[msg.messageId] = msg
    }
    return ordered.mapNotNull { byId[it] }
}
