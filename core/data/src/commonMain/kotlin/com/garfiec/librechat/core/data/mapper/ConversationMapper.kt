package com.garfiec.librechat.core.data.mapper

import com.garfiec.librechat.core.data.db.entity.ConversationEntity
import com.garfiec.librechat.core.model.Conversation
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import kotlin.time.Clock
import kotlin.time.Instant

private val json = Json { ignoreUnknownKeys = true }

fun Conversation.toEntity(): ConversationEntity = ConversationEntity(
    conversationId = conversationId ?: "",
    title = title ?: "New Chat",
    user = user ?: "",
    endpoint = endpoint,
    endpointType = endpointType,
    model = model,
    agentId = agentId,
    isArchived = isArchived,
    pinned = pinned ?: false,
    chatProjectId = chatProjectId,
    tags = json.encodeToString(ListSerializer(serializer<String>()), tags),
    iconURL = iconURL,
    greeting = greeting,
    modelParams = null,
    createdAt = parseTimestamp(createdAt),
    updatedAt = parseTimestamp(updatedAt),
)

fun ConversationEntity.toModel(): Conversation = Conversation(
    conversationId = conversationId,
    title = title,
    user = user,
    endpoint = endpoint,
    endpointType = endpointType,
    model = model,
    agentId = agentId,
    isArchived = isArchived,
    pinned = pinned,
    chatProjectId = chatProjectId,
    tags = try {
        json.decodeFromString<List<String>>(tags)
    } catch (_: Exception) {
        emptyList()
    },
    iconURL = iconURL,
    greeting = greeting,
    createdAt = formatTimestamp(createdAt),
    updatedAt = formatTimestamp(updatedAt),
)

fun List<ConversationEntity>.toModels(): List<Conversation> = map { it.toModel() }

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
