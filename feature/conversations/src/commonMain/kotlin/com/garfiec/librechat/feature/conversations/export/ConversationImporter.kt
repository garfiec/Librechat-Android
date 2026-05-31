package com.garfiec.librechat.feature.conversations.export

import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.model.ConversationExport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

class ConversationImporter(
    private val ioDispatcher: CoroutineDispatcher,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    suspend fun parseJson(jsonString: String): Result<ConversationExport> {
        return try {
            val export = withContext(ioDispatcher) {
                json.decodeFromString(ConversationExport.serializer(), jsonString)
            }
            if (export.conversation.conversationId == null) {
                return Result.Error(message = "Invalid export: missing conversation ID")
            }
            if (export.messages.isEmpty()) {
                return Result.Error(message = "Invalid export: no messages found")
            }
            Result.Success(export)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.Error(e, "Failed to parse conversation file: ${e.message}")
        }
    }
}
