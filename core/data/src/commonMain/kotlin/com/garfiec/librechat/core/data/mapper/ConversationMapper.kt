package com.garfiec.librechat.core.data.mapper

import com.garfiec.librechat.core.data.db.entity.ConversationEntity
import com.garfiec.librechat.core.model.Conversation
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import kotlin.time.Clock
import kotlin.time.Instant

private val json = Json { ignoreUnknownKeys = true }

fun Conversation.toEntity(): ConversationEntity {
    // The entity columns are non-null and drive `ORDER BY updatedAt DESC` — a null timestamp
    // must stamp "now" rather than 0L, or the row sinks to the bottom of the list. One shared
    // read so a conversation missing both timestamps gets a consistent pair.
    val now = Clock.System.now().toEpochMilliseconds()
    return ConversationEntity(
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
        createdAt = createdAt?.toEpochMilliseconds() ?: now,
        updatedAt = updatedAt?.toEpochMilliseconds() ?: now,
    )
}

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
    createdAt = Instant.fromEpochMilliseconds(createdAt),
    updatedAt = Instant.fromEpochMilliseconds(updatedAt),
)

fun List<ConversationEntity>.toModels(): List<Conversation> = map { it.toModel() }
