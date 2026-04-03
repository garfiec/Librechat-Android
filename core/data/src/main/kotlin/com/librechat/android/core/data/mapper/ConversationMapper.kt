package com.librechat.android.core.data.mapper

import com.librechat.android.core.data.db.entity.ConversationEntity
import com.librechat.android.core.model.Conversation
import com.librechat.android.core.model.EModelEndpoint
import com.librechat.android.core.model.toSerialName
import kotlinx.serialization.json.Json

private val json = Json { ignoreUnknownKeys = true }

/**
 * Older Room rows stored [EModelEndpoint.name] (e.g. `OPENAI`) instead of the API wire string (`openAI`).
 * Map those to serial names; pass through YAML / custom keys as-is.
 */
private fun normalizeStoredEndpoint(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    EModelEndpoint.entries.find { it.name == raw }?.let { return it.toSerialName() }
    return raw
}

fun Conversation.toEntity(): ConversationEntity = ConversationEntity(
    conversationId = conversationId ?: "",
    title = title ?: "New Chat",
    user = user ?: "",
    endpoint = endpoint,
    endpointType = endpointType,
    model = model,
    agentId = agentId,
    isArchived = isArchived,
    tags = json.encodeToString(kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.serializer<String>()), tags),
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
    endpoint = normalizeStoredEndpoint(endpoint),
    endpointType = normalizeStoredEndpoint(endpointType),
    model = model,
    agentId = agentId,
    isArchived = isArchived,
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
    if (dateString == null) return System.currentTimeMillis()
    return try {
        java.time.Instant.parse(dateString).toEpochMilli()
    } catch (_: Exception) {
        System.currentTimeMillis()
    }
}

private fun formatTimestamp(epochMillis: Long): String {
    return java.time.Instant.ofEpochMilli(epochMillis).toString()
}
