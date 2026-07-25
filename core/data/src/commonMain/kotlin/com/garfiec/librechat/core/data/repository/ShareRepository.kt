package com.garfiec.librechat.core.data.repository

import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.model.SharedLink
import com.garfiec.librechat.core.model.response.ForkConversationResponse
import com.garfiec.librechat.core.model.response.SharedLinksResponse

interface ShareRepository {
    suspend fun createShareLink(conversationId: String): Result<String>
    suspend fun getSharedLinksPaginated(cursor: String? = null): Result<SharedLinksResponse>
    suspend fun toggleShareVisibility(shareId: String): Result<SharedLink>
    suspend fun deleteShareLink(shareId: String): Result<Unit>

    /**
     * Copies a shared conversation into the caller's account so they can carry it on
     * (`POST /api/share/:shareId/fork`, v0.8.8 line).
     *
     * Distinct from [ConversationRepository.forkConversation], which needs the caller to already
     * own the source; this forks under the share link's own ACL. [targetMessageIndex] is a
     * position in the shared message list — a recipient never sees server message ids — and null
     * copies the whole thing.
     *
     * Wired but not yet surfaced: mobile has no shared-conversation viewer to fork *from*.
     */
    suspend fun forkSharedConversation(
        shareId: String,
        targetMessageIndex: Int? = null,
    ): Result<ForkConversationResponse>
}
