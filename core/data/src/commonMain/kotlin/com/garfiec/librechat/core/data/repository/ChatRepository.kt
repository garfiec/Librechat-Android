package com.garfiec.librechat.core.data.repository

import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.model.FileReference
import com.garfiec.librechat.core.model.StreamEvent
import com.garfiec.librechat.core.model.request.AddedConversation
import com.garfiec.librechat.core.model.request.EphemeralAgent
import com.garfiec.librechat.core.model.response.ChatStatusResponse
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject

interface ChatRepository {
    fun startChat(
        text: String,
        conversationId: String?,
        endpoint: String,
        endpointType: String? = null,
        key: String? = null,
        modelDisplayLabel: String? = null,
        model: String?,
        userMessageId: String? = null,
        parentMessageId: String? = null,
        agentId: String? = null,
        overrideParentMessageId: String? = null,
        responseMessageId: String? = null,
        isEdited: Boolean = false,
        isRegenerate: Boolean = false,
        isContinued: Boolean = false,
        webSearch: Boolean = false,
        files: List<FileReference>? = null,
        addedConvo: AddedConversation? = null,
        ephemeralAgent: EphemeralAgent? = null,
        isTemporary: Boolean = false,
        modelParams: JsonObject? = null,
    ): Flow<StreamEvent>

    /**
     * Asks the server to stop the in-flight turn. The response is only an ack — the stopped
     * turn arrives as an `aborted` final frame on the SSE stream, which must stay open.
     *
     * [streamId] may be null (Stop before the `created` milestone assigns a conversation id):
     * when no id resolves, the abort route falls back to the caller's most recent active job.
     *
     * [isTemporary] is forwarded so the server stamps the partial it persists with the temp-chat
     * expiry; omitting it leaves the row with no TTL. See [ChatAbortRequest].
     */
    suspend fun abortChat(streamId: String?, isTemporary: Boolean = false): Result<Unit>
    suspend fun checkStreamStatus(conversationId: String): ChatStatusResponse
    fun resumeStream(conversationId: String): Flow<StreamEvent>
}
