package com.garfiec.librechat.core.data.repository

import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.model.FileObject
import com.garfiec.librechat.core.model.request.DeleteFileEntry
import com.garfiec.librechat.core.model.response.FilePreviewResponse
import com.garfiec.librechat.core.model.response.FileUploadConfig

interface FileRepository {
    suspend fun getFiles(): Result<List<FileObject>>

    /** Fetches the server's upload config (`GET /api/files/config`), including the
     *  per-endpoint `endpoints` map. Used to gate the chat attach controls. */
    suspend fun getFileConfig(): Result<FileUploadConfig>
    suspend fun uploadFile(
        bytes: ByteArray,
        filename: String,
        type: String,
        onProgress: ((Float) -> Unit)? = null,
    ): Result<FileObject>
    suspend fun uploadFile(
        bytes: ByteArray,
        filename: String,
        type: String,
        fileId: String? = null,
        endpoint: String? = null,
        model: String? = null,
        agentId: String? = null,
        toolResource: String? = null,
        messageFile: Boolean? = null,
        width: Int? = null,
        height: Int? = null,
        onProgress: ((Float) -> Unit)? = null,
    ): Result<FileObject>
    suspend fun deleteFiles(
        files: List<DeleteFileEntry>,
        agentId: String? = null,
        toolResource: String? = null,
    ): Result<Unit>
    suspend fun downloadFile(userId: String, fileId: String): Result<ByteArray>
    suspend fun getAgentFiles(agentId: String): Result<List<FileObject>>

    /**
     * Polls `GET /api/files/:fileId/preview` until the status is terminal
     * (`ready`/`failed`) or the attempt budget is exhausted, then returns the
     * last [FilePreviewResponse]. Used by the deferred office-doc preview flow.
     */
    suspend fun pollFilePreview(fileId: String): Result<FilePreviewResponse>

    /**
     * Pushes back the upload-window TTL on files that are attached to something not yet sent
     * (v0.8.8 line). Best-effort: a server without the route, or ids it does not recognize,
     * changes nothing — send-time marking is still the backstop.
     */
    suspend fun markFilesUsed(fileIds: List<String>): Result<Unit>

    /**
     * Whether the usage-hold route is worth calling. Callers that would otherwise schedule
     * recurring work ask first: [markFilesUsed] already no-ops when the route is absent, but a
     * heartbeat driving it would keep waking for the life of its owner to do nothing.
     *
     * False only when the route is KNOWN missing — a build commit that resolved to a tag below
     * v0.8.8-rc1, or a touch that already 404'd. A server the version gate cannot place answers
     * true and the first touch settles it, so a recurring caller must re-ask on each tick rather
     * than only before starting: the answer can flip from true to false once, when the probe
     * lands.
     */
    fun supportsUsageHold(): Boolean

    /**
     * Drops what this repository learned about the CURRENT server — today, the usage-hold probe
     * verdict. Call on account/server switch: the repository is an app-wide singleton, so without
     * this a "route missing" answer discovered on the outgoing server would suppress the hold on
     * the incoming one.
     */
    fun clear()
}
