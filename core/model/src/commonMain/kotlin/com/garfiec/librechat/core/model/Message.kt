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
    // Skills invoked on this turn (v0.8.6). UI-metadata only — round-tripped so
    // the selection survives reload; pill rendering ships with the Skills
    // feature. [manualSkills] = user-invoked via the `$` popover this turn;
    // [alwaysAppliedSkills] = auto-primed via frontmatter `always-apply`.
    val manualSkills: List<String>? = null,
    val alwaysAppliedSkills: List<String>? = null,
    // Verbatim excerpts the user referenced on this turn (v0.8.7 "reference selected
    // text"). Persisted UI-metadata; the web client creates them and merges them into
    // the sent text. Mobile renders them on the user bubble (no creation affordance yet).
    val quotes: List<String>? = null,
)
