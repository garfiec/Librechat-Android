package com.garfiec.librechat.core.network.api

import com.garfiec.librechat.core.model.SharedLink
import com.garfiec.librechat.core.model.request.CreateShareRequest
import com.garfiec.librechat.core.model.response.SharedLinksResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.path

class ShareApi constructor(
    private val client: HttpClient,
) {
    suspend fun getSharedLinks(cursor: String? = null, isPublic: Boolean? = null): SharedLinksResponse =
        client.get {
            url { path("api/share") }
            parameter("cursor", cursor)
            parameter("pageSize", 25)
            // Older backends (< 0.8.7) filter the route on `isPublic`, defaulting to false when
            // absent — so the param must be sent explicitly there. Ktor omits null parameters.
            parameter("isPublic", isPublic)
        }.body()

    suspend fun createShareLink(
        conversationId: String,
        targetMessageId: String? = null,
    ): SharedLink =
        client.post {
            url { path("api/share/$conversationId") }
            setBody(CreateShareRequest(targetMessageId = targetMessageId))
        }.body()

    suspend fun toggleShareVisibility(shareId: String): SharedLink =
        client.patch {
            url { path("api/share/$shareId") }
        }.body()

    suspend fun deleteShareLink(shareId: String) {
        client.delete {
            url { path("api/share/$shareId") }
        }
    }
}
