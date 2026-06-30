package com.garfiec.librechat.core.network.api

import com.garfiec.librechat.core.model.Conversation
import com.garfiec.librechat.core.model.request.ArchiveConversationArg
import com.garfiec.librechat.core.model.request.ArchiveConversationRequest
import com.garfiec.librechat.core.model.request.ConvoDeleteArg
import com.garfiec.librechat.core.model.request.ConvoDeleteBody
import com.garfiec.librechat.core.model.request.ConvoPinArg
import com.garfiec.librechat.core.model.request.ConvoPinBody
import com.garfiec.librechat.core.model.request.ConvoUpdateArg
import com.garfiec.librechat.core.model.request.ConvoUpdateBody
import com.garfiec.librechat.core.model.request.DuplicateConversationRequest
import com.garfiec.librechat.core.model.request.ForkConversationRequest
import com.garfiec.librechat.core.model.response.ConversationListResponse
import com.garfiec.librechat.core.model.response.ForkConversationResponse
import com.garfiec.librechat.core.model.response.GenerateTitleResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.path

class ConversationsApi constructor(
    private val client: HttpClient,
) {
    suspend fun getConversations(
        cursor: String? = null,
        limit: Int = 25,
        isArchived: Boolean = false,
        tags: List<String>? = null,
        search: String? = null,
        sortBy: String? = null,
        sortDirection: String? = null,
        projectId: String? = null,
    ): ConversationListResponse =
        client.get {
            url { path("api/convos") }
            parameter("cursor", cursor)
            parameter("limit", limit)
            parameter("isArchived", isArchived)
            // v0.8.7: filter by Chat Project. Accepts a project id or the literal "unassigned".
            projectId?.let { parameter("projectId", it) }
            // Upstream's route handler reads req.query.tags directly, so we send
            // repeated `tags=value` params rather than PHP-style `tags[]=value`.
            tags?.forEach { tag -> parameter("tags", tag) }
            search?.let { parameter("search", it) }
            sortBy?.let { parameter("sortBy", it) }
            sortDirection?.let { parameter("sortDirection", it) }
        }.body()

    suspend fun getConversation(conversationId: String): Conversation =
        client.get {
            url { path("api/convos/$conversationId") }
        }.body()

    suspend fun updateTitle(conversationId: String, title: String): Conversation =
        client.post {
            url { path("api/convos/update") }
            setBody(
                ConvoUpdateBody(
                    arg = ConvoUpdateArg(
                        conversationId = conversationId,
                        title = title,
                    ),
                ),
            )
        }.body()

    suspend fun archive(conversationId: String, isArchived: Boolean): Conversation =
        client.post {
            url { path("api/convos/archive") }
            setBody(
                ArchiveConversationRequest(
                    arg = ArchiveConversationArg(
                        conversationId = conversationId,
                        isArchived = isArchived,
                    ),
                ),
            )
        }.body()

    suspend fun pin(conversationId: String, pinned: Boolean): Conversation =
        client.post {
            url { path("api/convos/pin") }
            setBody(
                ConvoPinBody(
                    arg = ConvoPinArg(
                        conversationId = conversationId,
                        pinned = pinned,
                    ),
                ),
            )
        }.body()

    suspend fun deleteConversation(conversationId: String) {
        client.delete {
            url { path("api/convos") }
            setBody(
                ConvoDeleteBody(
                    arg = ConvoDeleteArg(
                        conversationId = conversationId,
                    ),
                ),
            )
        }
    }

    suspend fun deleteAllConversations() {
        client.delete {
            url { path("api/convos/all") }
        }
    }

    suspend fun importConversations(
        fileBytes: ByteArray,
        filename: String,
        contentType: String = "application/json",
    ): Conversation =
        client.submitFormWithBinaryData(
            formData = formData {
                append("file", fileBytes, Headers.build {
                    append(HttpHeaders.ContentDisposition, "filename=\"$filename\"")
                    append(HttpHeaders.ContentType, contentType)
                })
            },
        ) {
            url { path("api/convos/import") }
        }.body()

    suspend fun generateTitle(conversationId: String): GenerateTitleResponse =
        client.get {
            url { path("api/convos/gen_title/$conversationId") }
        }.body()

    suspend fun forkConversation(request: ForkConversationRequest): ForkConversationResponse =
        client.post {
            url { path("api/convos/fork") }
            setBody(request)
        }.body()

    suspend fun duplicateConversation(conversationId: String, title: String?): Conversation =
        client.post {
            url { path("api/convos/duplicate") }
            setBody(DuplicateConversationRequest(conversationId = conversationId, title = title))
        }.body()
}
