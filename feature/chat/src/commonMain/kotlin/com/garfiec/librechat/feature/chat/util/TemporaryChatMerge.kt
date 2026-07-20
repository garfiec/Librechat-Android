package com.garfiec.librechat.feature.chat.util

import com.garfiec.librechat.core.model.Message

/**
 * Merges the final request/response [Message]s from an SSE Final event into the existing
 * in-memory message list — WITHOUT any DB round-trip. A message whose id is already present
 * (the optimistic user message / streaming placeholder) is folded over the local copy via
 * [mergedOver] — never a wholesale replace, so a poorer server frame can't strip fields the
 * local copy already holds richer. Genuinely new messages are appended, keeping insertion
 * order stable.
 *
 * Used by every finalize (normal and temporary chats). Temp chats additionally rely on it
 * being pure: they are never persisted to Room (a data-at-rest leak would otherwise survive
 * even though the conversation is hidden from history), so their display is driven entirely
 * from this merge with no `loadConversation` read-through behind it.
 */
fun mergeFinalMessagesInMemory(
    existing: List<Message>,
    finalMessages: List<Message>,
): List<Message> {
    val byId = existing.associateBy { it.messageId }.toMutableMap()
    val ordered = existing.map { it.messageId }.toMutableList()
    for (msg in finalMessages) {
        val local = byId[msg.messageId]
        if (local == null) ordered.add(msg.messageId)
        byId[msg.messageId] = local?.let { msg.mergedOver(it) } ?: msg
    }
    return ordered.mapNotNull { byId[it] }
}

/**
 * Field-level monotonic merge for applying a stream-final [Message] over the in-memory copy:
 * the incoming (receiver) value wins wherever it carries information, and an absent/blank
 * incoming field keeps [local]'s. This is what lets a skeletal aborted frame's request
 * (id/parent/text/quotes only) *gap-fill* the optimistic user message instead of stripping
 * its attachments, files, sender, and createdAt — one rule instead of a guard per field.
 *
 * ONLY valid at the final-frame chokepoint, where the incoming record is known-possibly-partial.
 * Full-record sync paths (`getMessages`, `refreshMessages`) must NOT use this — their nulls are
 * meaningful ("the server cleared it") and merging would make staleness sticky.
 *
 * Deliberate exceptions to `incoming ?: local`:
 * - identity ([Message.messageId], [Message.conversationId], [Message.isCreatedByUser]): incoming,
 *   equal by construction (the merge is keyed by id).
 * - [Message.error] / [Message.unfinished]: ALWAYS incoming — they are server truth about the
 *   turn's terminal state, and an aborted frame's `unfinished = true` must win over the
 *   optimistic default `false`.
 * - [Message.text] / [Message.sender]: non-blank incoming wins; blank keeps local (they are
 *   non-null in the model, so `?:` can't express absence).
 */
fun Message.mergedOver(local: Message): Message = copy(
    parentMessageId = parentMessageId ?: local.parentMessageId,
    responseMessageId = responseMessageId ?: local.responseMessageId,
    overrideParentMessageId = overrideParentMessageId ?: local.overrideParentMessageId,
    user = user ?: local.user,
    model = model ?: local.model,
    endpoint = endpoint ?: local.endpoint,
    sender = sender?.takeIf { it.isNotBlank() } ?: local.sender,
    text = text.ifBlank { local.text },
    finishReason = finishReason ?: local.finishReason,
    tokenCount = tokenCount ?: local.tokenCount,
    iconURL = iconURL ?: local.iconURL,
    content = content ?: local.content,
    files = files ?: local.files,
    attachments = attachments ?: local.attachments,
    feedback = feedback ?: local.feedback,
    threadId = threadId ?: local.threadId,
    metadata = metadata ?: local.metadata,
    contextMeta = contextMeta ?: local.contextMeta,
    createdAt = createdAt ?: local.createdAt,
    updatedAt = updatedAt ?: local.updatedAt,
    title = title ?: local.title,
    manualSkills = manualSkills ?: local.manualSkills,
    alwaysAppliedSkills = alwaysAppliedSkills ?: local.alwaysAppliedSkills,
    quotes = quotes ?: local.quotes,
)
