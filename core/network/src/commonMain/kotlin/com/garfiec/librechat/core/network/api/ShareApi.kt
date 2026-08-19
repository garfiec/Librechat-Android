package com.garfiec.librechat.core.network.api

import com.garfiec.librechat.core.model.SharedLink
import com.garfiec.librechat.core.model.request.CreateShareRequest
import com.garfiec.librechat.core.model.request.ForkSharedConversationRequest
import com.garfiec.librechat.core.model.response.ForkConversationResponse
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

    /**
     * PATCH /api/share/:shareId — re-publishes the link against the conversation's current state.
     *
     * **Not a visibility toggle, and not a refresh.** From v0.8.8-rc1 the `shareId` is stable
     * across a re-publish and the link keeps working; what changes is the content behind it.
     * Earlier servers mint a new id and orphan the URL already handed out.
     *
     * Requires the **SHARED_LINKS CREATE** permission — re-scoping a link re-publishes
     * conversation content, so revoking CREATE has to stop updates too. `DELETE` on this route
     * stays ungated, so a role that may no longer re-publish may still revoke. A role with
     * `SHARED_LINKS.USE` but not `CREATE` gets 403 here where it previously succeeded.
     *
     * Sends no body deliberately. The route would also accept `targetMessageId` (non-empty
     * string) and `snapshotFiles` (boolean), each 400 on a wrong type.
     */
    suspend fun updateShareLink(shareId: String): SharedLink =
        client.patch {
            url { path("api/share/$shareId") }
        }.body()

    suspend fun deleteShareLink(shareId: String) {
        client.delete {
            url { path("api/share/$shareId") }
        }
    }

    /**
     * Copies a shared conversation into the caller's own account so they can continue it
     * (v0.8.8 line, #13714).
     *
     * Not the same route as `POST /api/convos/fork`, and not reachable through it: that one
     * forks a conversation the caller already owns, this one forks from a share link under the
     * share's own ACL (`canAccessSharedLink`), which is how a recipient who owns nothing gets a
     * copy. It requires a signed-in caller and is rate-limited per IP and per user.
     *
     * [targetMessageIndex] cuts the copy off after that many messages; omitted, the whole
     * conversation is copied. The response is the same `{conversation, messages}` pair the
     * owner-side fork returns.
     */
    suspend fun forkSharedConversation(
        shareId: String,
        targetMessageIndex: Int? = null,
    ): ForkConversationResponse =
        client.post {
            url { path("api/share/$shareId/fork") }
            setBody(ForkSharedConversationRequest(targetMessageIndex))
        }.body()
}
