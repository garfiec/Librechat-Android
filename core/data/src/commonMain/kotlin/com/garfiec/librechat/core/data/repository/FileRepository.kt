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
}
