package com.garfiec.librechat.core.data.mapper

import com.garfiec.librechat.core.data.db.entity.MessageEntity
import com.garfiec.librechat.core.model.Attachment
import com.garfiec.librechat.core.model.Feedback
import com.garfiec.librechat.core.model.FeedbackTag
import com.garfiec.librechat.core.model.FileReference
import com.garfiec.librechat.core.model.Message
import com.garfiec.librechat.core.model.MinimalFeedback
import com.garfiec.librechat.core.model.content.MessageContentPart
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.time.Clock
import kotlin.time.Instant

private val json = Json { ignoreUnknownKeys = true }

/**
 * Encodes a feedback submission for `MessageEntity.feedback`, exactly as [toModel] decodes it.
 *
 * Lives beside that decode on purpose: the write used to store a bare rating string into a column
 * this file round-trips as `Feedback` JSON, so every read threw and the decode's catch turned the
 * user's thumb back into null. Encoding anywhere else lets the pair drift again.
 */
fun feedbackColumnValue(feedback: MinimalFeedback?): String? = feedback?.let {
    json.encodeToString(
        Feedback.serializer(),
        Feedback(
            rating = it.rating,
            tag = json.encodeToJsonElement(FeedbackTag.serializer(), it.tag),
            text = it.text,
        ),
    )
}

fun Message.toEntity(): MessageEntity = MessageEntity(
    messageId = messageId,
    conversationId = conversationId,
    parentMessageId = parentMessageId,
    sender = sender,
    text = text,
    content = content?.let { json.encodeToString(ListSerializer(MessageContentPart.serializer()), it) },
    isCreatedByUser = isCreatedByUser,
    model = model,
    endpoint = endpoint,
    iconURL = iconURL,
    unfinished = unfinished,
    error = error,
    finishReason = finishReason,
    tokenCount = tokenCount,
    feedback = feedback?.let { json.encodeToString(Feedback.serializer(), it) },
    files = files?.let { json.encodeToString(ListSerializer(FileReference.serializer()), it) },
    attachments = attachments?.let { json.encodeToString(ListSerializer(Attachment.serializer()), it) },
    metadata = metadata?.toString(),
    quotes = quotes?.let { json.encodeToString(ListSerializer(String.serializer()), it) },
    createdAt = parseTimestamp(createdAt),
    updatedAt = parseTimestamp(updatedAt),
)

fun MessageEntity.toModel(): Message = Message(
    messageId = messageId,
    conversationId = conversationId,
    parentMessageId = parentMessageId,
    sender = sender,
    text = text ?: "",
    content = content?.let {
        try { json.decodeFromString<List<MessageContentPart>>(it) } catch (_: Exception) { null }
    },
    isCreatedByUser = isCreatedByUser,
    model = model,
    endpoint = endpoint,
    iconURL = iconURL,
    unfinished = unfinished,
    error = error,
    finishReason = finishReason,
    tokenCount = tokenCount,
    feedback = feedback?.let {
        try { json.decodeFromString<Feedback>(it) } catch (_: Exception) { null }
    },
    files = files?.let {
        try { json.decodeFromString<List<FileReference>>(it) } catch (_: Exception) { null }
    },
    attachments = attachments?.let {
        try { json.decodeFromString<List<Attachment>>(it) } catch (_: Exception) { null }
    },
    metadata = metadata?.let {
        try { json.decodeFromString<JsonObject>(it) } catch (_: Exception) { null }
    },
    quotes = quotes?.let {
        try { json.decodeFromString<List<String>>(it) } catch (_: Exception) { null }
    },
    createdAt = formatTimestamp(createdAt),
    updatedAt = formatTimestamp(updatedAt),
)

fun List<MessageEntity>.toModels(): List<Message> = map { it.toModel() }

private fun parseTimestamp(dateString: String?): Long {
    if (dateString == null) return Clock.System.now().toEpochMilliseconds()
    return try {
        Instant.parse(dateString).toEpochMilliseconds()
    } catch (_: Exception) {
        Clock.System.now().toEpochMilliseconds()
    }
}

private fun formatTimestamp(epochMillis: Long): String {
    return Instant.fromEpochMilliseconds(epochMillis).toString()
}
