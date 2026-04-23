package com.garfiec.librechat.core.model

import com.garfiec.librechat.core.model.content.MessageContentPart
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class Message(
    val messageId: String,
    val conversationId: String,
    val parentMessageId: String? = null,
    val responseMessageId: String? = null,
    val overrideParentMessageId: String? = null,
    val user: String? = null,
    val model: String? = null,
    val endpoint: String? = null,
    val sender: String? = null,
    val text: String = "",
    val isCreatedByUser: Boolean = false,
    val error: Boolean = false,
    val unfinished: Boolean = false,
    @SerialName("finish_reason") val finishReason: String? = null,
    val tokenCount: Int? = null,
    val iconURL: String? = null,
    val content: List<MessageContentPart>? = null,
    val files: List<FileReference>? = null,
    val attachments: List<Attachment>? = null,
    val feedback: Feedback? = null,
    @SerialName("thread_id") val threadId: String? = null,
    val metadata: JsonObject? = null,
    val contextMeta: JsonObject? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val title: String? = null,
)
