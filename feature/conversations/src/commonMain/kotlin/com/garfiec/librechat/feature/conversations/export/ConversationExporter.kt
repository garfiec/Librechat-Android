package com.garfiec.librechat.feature.conversations.export

import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.data.repository.ConversationRepository
import com.garfiec.librechat.core.data.repository.MessageRepository
import com.garfiec.librechat.core.model.ConversationExport
import com.garfiec.librechat.core.model.Message
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import kotlin.time.Clock

class ConversationExporter(
    private val conversationRepository: ConversationRepository,
    private val messageRepository: MessageRepository,
    private val ioDispatcher: CoroutineDispatcher,
) {
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    suspend fun exportAsJson(conversationId: String): Result<String> {
        val conversation = when (val result = conversationRepository.getConversation(conversationId, originAccount = null)) {
            is Result.Success -> result.data
            is Result.Error -> return Result.Error(result.exception, result.message)
            is Result.Loading -> return Result.Error(message = "Unexpected loading state")
        }
        val messages = when (val result = messageRepository.getMessages(conversationId)) {
            is Result.Success -> result.data
            is Result.Error -> return Result.Error(result.exception, result.message)
            is Result.Loading -> return Result.Error(message = "Unexpected loading state")
        }
        val export = ConversationExport(
            conversation = conversation,
            messages = messages,
            exportedAt = Clock.System.now().toEpochMilliseconds(),
            version = 1,
        )
        val encoded = withContext(ioDispatcher) {
            json.encodeToString(ConversationExport.serializer(), export)
        }
        return Result.Success(encoded)
    }

    suspend fun exportAsMarkdown(conversationId: String): Result<String> {
        val conversation = when (val result = conversationRepository.getConversation(conversationId, originAccount = null)) {
            is Result.Success -> result.data
            is Result.Error -> return Result.Error(result.exception, result.message)
            is Result.Loading -> return Result.Error(message = "Unexpected loading state")
        }
        val messages = when (val result = messageRepository.getMessages(conversationId)) {
            is Result.Success -> result.data
            is Result.Error -> return Result.Error(result.exception, result.message)
            is Result.Loading -> return Result.Error(message = "Unexpected loading state")
        }

        val markdown = withContext(ioDispatcher) {
            val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
            val month = now.monthNumber.toString().padStart(2, '0')
            val day = now.dayOfMonth.toString().padStart(2, '0')
            val hour = now.hour.toString().padStart(2, '0')
            val minute = now.minute.toString().padStart(2, '0')
            val exportDate = "${now.year}-$month-$day $hour:$minute"

            val sb = StringBuilder()
            sb.appendLine("# ${conversation.title ?: "Untitled Conversation"}")
            sb.appendLine("Exported on $exportDate")
            sb.appendLine()

            for (message in messages) {
                val role = if (message.isCreatedByUser) "User" else "Assistant"
                sb.appendLine("## $role")
                sb.appendLine(extractMessageText(message))
                sb.appendLine()
            }
            sb.toString().trimEnd()
        }

        return Result.Success(markdown)
    }

    private fun extractMessageText(message: Message): String {
        // Prefer content parts if available, otherwise fall back to text
        val parts = message.content
        if (!parts.isNullOrEmpty()) {
            return parts.mapNotNull { part ->
                part.text ?: part.think?.let { "[Thinking] $it" }
            }.joinToString("\n")
        }
        return message.text
    }
}
