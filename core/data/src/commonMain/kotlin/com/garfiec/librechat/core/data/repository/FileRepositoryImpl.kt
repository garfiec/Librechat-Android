package com.garfiec.librechat.core.data.repository

import com.garfiec.librechat.core.common.BackendVersion
import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.common.result.safeApiCall
import com.garfiec.librechat.core.model.FileObject
import com.garfiec.librechat.core.model.request.DeleteFileEntry
import com.garfiec.librechat.core.model.request.DeleteFilesRequest
import com.garfiec.librechat.core.model.response.FilePreviewResponse
import com.garfiec.librechat.core.model.response.FileUploadConfig
import com.garfiec.librechat.core.network.api.FILES_USAGE_MAX_IDS
import com.garfiec.librechat.core.network.api.FilesApi
import com.garfiec.librechat.core.network.api.FilesExtApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class FileRepositoryImpl(
    private val filesApi: FilesApi,
    private val filesExtApi: FilesExtApi,
    private val configRepository: ConfigRepository,
) : FileRepository {

    override suspend fun getFiles(): Result<List<FileObject>> =
        safeApiCall { filesApi.getFiles() }

    override suspend fun getFileConfig(): Result<FileUploadConfig> =
        safeApiCall { filesApi.getFileConfig() }

    override suspend fun uploadFile(
        bytes: ByteArray,
        filename: String,
        type: String,
        onProgress: ((Float) -> Unit)?,
    ): Result<FileObject> =
        safeApiCall {
            filesApi.uploadFile(
                bytes = bytes,
                filename = filename,
                type = type,
                // file_id and endpoint are required by the backend; provide defaults
                fileId = Uuid.random().toString(),
                endpoint = "agents",
                onProgress = onProgress,
            )
        }

    override suspend fun uploadFile(
        bytes: ByteArray,
        filename: String,
        type: String,
        fileId: String?,
        endpoint: String?,
        model: String?,
        agentId: String?,
        toolResource: String?,
        messageFile: Boolean?,
        width: Int?,
        height: Int?,
        onProgress: ((Float) -> Unit)?,
    ): Result<FileObject> =
        safeApiCall {
            filesApi.uploadFile(
                bytes = bytes,
                filename = filename,
                type = type,
                fileId = fileId ?: Uuid.random().toString(),
                endpoint = endpoint,
                model = model,
                agentId = agentId,
                toolResource = toolResource,
                messageFile = messageFile,
                width = width,
                height = height,
                onProgress = onProgress,
            )
        }

    override suspend fun deleteFiles(
        files: List<DeleteFileEntry>,
        agentId: String?,
        toolResource: String?,
    ): Result<Unit> =
        safeApiCall {
            filesApi.deleteFiles(
                DeleteFilesRequest(
                    files = files,
                    agentId = agentId,
                    toolResource = toolResource,
                ),
            )
        }

    /**
     * Prefers the direct/presigned download URL (v0.8.6 — S3/CloudFront) so the
     * bytes come straight from the CDN instead of proxying through LibreChat.
     * Falls back to the `/download` proxy whenever the URL path is unavailable:
     * the endpoint 501s for sources with no direct-URL strategy (local storage),
     * 400s for OpenAI-storage files missing a model, and any transport error on
     * the CDN fetch should still yield a working download via the proxy. Only
     * the final proxy result surfaces through [safeApiCall] error mapping; the
     * URL attempt's failures are swallowed (they're expected on non-CDN servers).
     */
    override suspend fun downloadFile(userId: String, fileId: String): Result<ByteArray> {
        try {
            val urlResponse = filesApi.getDownloadUrl(userId, fileId)
            return Result.Success(filesApi.downloadFromUrl(urlResponse.url))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Expected on local-storage / OpenAI-storage / non-CDN servers, or a
            // transient CDN failure — fall through to the server-proxy download.
        }
        return safeApiCall { filesApi.downloadFile(userId, fileId) }
    }

    override suspend fun getAgentFiles(agentId: String): Result<List<FileObject>> =
        safeApiCall { filesExtApi.getAgentFiles(agentId) }

    /**
     * Polls the preview endpoint until terminal or the attempt budget is spent.
     * Budget (~POLL_MAX_ATTEMPTS × POLL_INTERVAL_MS ≈ 60s) brackets the server's
     * lazy-sweep cutoff, so a stuck-pending record resolves to `failed` within
     * the loop rather than spinning forever. A non-terminal final poll is still
     * returned as Success(status="pending") — the caller decides how to surface
     * a slow/never-resolving preview. Transport errors surface via [safeApiCall].
     */
    override suspend fun pollFilePreview(fileId: String): Result<FilePreviewResponse> {
        var attempt = 0
        while (true) {
            when (val result = safeApiCall { filesApi.getFilePreview(fileId) }) {
                is Result.Success -> {
                    if (result.data.isTerminal || attempt >= POLL_MAX_ATTEMPTS) return result
                }
                is Result.Error -> return result
                is Result.Loading -> { /* safeApiCall never emits Loading */ }
            }
            attempt++
            delay(POLL_INTERVAL_MS)
        }
    }

    /**
     * Version-gated because the cost of guessing wrong is not a bare 404. Pre-0.8.8 servers apply
     * `fileUploadIpLimiter` + `fileUploadUserLimiter` to every POST under `/api/files` except
     * `/speech` — the `/usage` exemption is part of the same 0.8.8-line change that added the
     * route — so an ungated touch against an older server both 404s and spends real upload quota
     * (plus a violation score) on a call that can never succeed. The touch is a best-effort TTL
     * push with send-time marking as its backstop, so suppressing it degrades to the behaviour
     * mobile had before the route existed.
     *
     * A date gate, not a version compare: the route landed on the untagged 0.8.8 line, where a dev
     * build carrying it still reports 0.8.7. Landing day itself rather than the day after — same
     * landing commit as the steering gate, so both read the same date — which means a same-day
     * predecessor is misread as having the route and pays one limited 404 per queued message with
     * attachments; rounding up would instead cost real 0.8.8 servers a day's worth of TTL pushes,
     * which is the failure that actually loses an attachment.
     */
    override fun supportsUsageHold(): Boolean = BackendVersion.supportsFeature(
        configRepository.detectedBackend.value,
        minVersion = "0.8.8-rc1",
        landedDate = "2026-07-14",
    )

    override suspend fun markFilesUsed(fileIds: List<String>): Result<Unit> {
        if (!supportsUsageHold()) {
            return Result.Success(Unit)
        }
        val ids = fileIds.filter { it.isNotBlank() }.distinct()
        if (ids.isEmpty()) return Result.Success(Unit)
        // The route caps a call at FILES_USAGE_MAX_IDS and 400s the whole batch past it, so
        // chunk rather than let one over-long queue item silently forfeit every touch in it.
        for (chunk in ids.chunked(FILES_USAGE_MAX_IDS)) {
            val result = safeApiCall { filesApi.markFilesUsed(chunk) }
            if (result is Result.Error) return result
        }
        return Result.Success(Unit)
    }

    private companion object {
        const val POLL_INTERVAL_MS = 2_000L
        const val POLL_MAX_ATTEMPTS = 30
    }
}
