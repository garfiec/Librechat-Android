package com.garfiec.librechat.core.data.repository

import com.garfiec.librechat.core.common.BackendVersion
import com.garfiec.librechat.core.common.FeatureSupport
import com.garfiec.librechat.core.common.result.ApiException
import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.common.result.onApiDispatcher
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
import kotlin.concurrent.Volatile
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
            // onApiDispatcher, not safeApiCall: safeApiCall would log every one of these expected
            // failures at error level. The dispatcher hop is still required (#326).
            return onApiDispatcher {
                val urlResponse = filesApi.getDownloadUrl(userId, fileId)
                Result.Success(filesApi.downloadFromUrl(urlResponse.url))
            }
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
     * What a single 404 from `POST /api/files/usage` taught us, on a server the version gate could
     * not place. `null` until the first such touch settles; false latches the route off for the
     * rest of this server session, true confirms it. Reset by [clear] on account/server switch.
     */
    @Volatile
    private var usageHoldProbeVerdict: Boolean? = null

    /**
     * True only when the route is KNOWN to be missing — a build commit that resolved to a tag
     * below v0.8.8-rc1, or a probe that already 404'd. An unplaceable server is not ruled out.
     *
     * Both directions cost something, which is why proof and doubt are separated. Calling a
     * server that lacks the route is not a free 404: pre-0.8.8 servers apply `fileUploadIpLimiter`
     * + `fileUploadUserLimiter` to every POST under `/api/files` except `/speech` (the `/usage`
     * exemption arrived with the route), so it spends real upload quota and a violation score. But
     * NOT calling a server that has it lets the upload-window reaper collect an attachment out
     * from under a queued message, and the send then references a file the server has deleted.
     *
     * So: rule the route out on proof, probe on doubt. A server the gate cannot place gets exactly
     * one touch, and its 404 latches the suppression.
     */
    private fun usageHoldRuledOut(): Boolean =
        usageHoldProbeVerdict == false || usageHoldSupport().isRuledOut

    private fun usageHoldSupport(): FeatureSupport = BackendVersion.featureSupport(
        configRepository.detectedBackend.value,
        minVersion = "0.8.8-rc1",
    )

    override fun supportsUsageHold(): Boolean = !usageHoldRuledOut()

    override suspend fun markFilesUsed(fileIds: List<String>): Result<Unit> {
        if (usageHoldRuledOut()) {
            return Result.Success(Unit)
        }
        val ids = fileIds.filter { it.isNotBlank() }.distinct()
        if (ids.isEmpty()) return Result.Success(Unit)
        // Whether THIS call is the one discovering the answer. A server the version gate already
        // placed as PRESENT is not probing: a 404 from it is a proxy or a deployment oddity, not
        // evidence about the release, and must not permanently disable the hold.
        val probing = usageHoldProbeVerdict == null && !usageHoldSupport().isPresent
        // The route caps a call at FILES_USAGE_MAX_IDS and 400s the whole batch past it, so
        // chunk rather than let one over-long queue item silently forfeit every touch in it.
        for (chunk in ids.chunked(FILES_USAGE_MAX_IDS)) {
            val result = safeApiCall { filesApi.markFilesUsed(chunk) }
            if (result is Result.Error) {
                if (probing && (result.exception as? ApiException)?.statusCode == HTTP_NOT_FOUND) {
                    usageHoldProbeVerdict = false
                    // Best-effort by contract (see [markFilesUsed]): a missing route is not a
                    // failure to report upward.
                    return Result.Success(Unit)
                }
                return result
            }
        }
        if (probing) usageHoldProbeVerdict = true
        return Result.Success(Unit)
    }

    override fun clear() {
        usageHoldProbeVerdict = null
    }

    private companion object {
        const val POLL_INTERVAL_MS = 2_000L
        const val POLL_MAX_ATTEMPTS = 30
        const val HTTP_NOT_FOUND = 404
    }
}
