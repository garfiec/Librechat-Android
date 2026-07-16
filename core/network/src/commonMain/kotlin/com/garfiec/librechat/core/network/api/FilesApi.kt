package com.garfiec.librechat.core.network.api

import co.touchlab.kermit.Logger
import com.garfiec.librechat.core.model.FileObject
import com.garfiec.librechat.core.model.request.DeleteFilesRequest
import com.garfiec.librechat.core.model.response.FileDownloadURLResponse
import com.garfiec.librechat.core.model.response.FilePreviewResponse
import com.garfiec.librechat.core.model.response.FileUploadConfig
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.onUpload
import io.ktor.client.plugins.timeout
import io.ktor.client.request.delete
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.path
import kotlinx.coroutines.CancellationException
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

class FilesApi constructor(
    private val client: HttpClient,
) {
    suspend fun getFiles(): List<FileObject> =
        client.get {
            url { path("api/files") }
        }.body()

    suspend fun getFileConfig(): FileUploadConfig =
        client.get {
            url { path("api/files/config") }
        }.body()

    @OptIn(ExperimentalUuidApi::class)
    suspend fun uploadFile(
        bytes: ByteArray,
        filename: String,
        type: String,
        fileId: String = Uuid.random().toString(),
        endpoint: String? = null,
        model: String? = null,
        agentId: String? = null,
        toolResource: String? = null,
        messageFile: Boolean? = null,
        width: Int? = null,
        height: Int? = null,
        onProgress: ((Float) -> Unit)? = null,
    ): FileObject {
        Logger.d("FilesApi") {
            "uploadFile: filename=$filename, type=$type, size=${bytes.size} bytes, fileId=$fileId, " +
                "endpoint=$endpoint, model=$model, agentId=$agentId, messageFile=$messageFile, width=$width, height=$height"
        }

        val multipart = MultiPartFormDataContent(
            formData {
                append("file", bytes, Headers.build {
                    append(HttpHeaders.ContentDisposition, "filename=\"${encodeFilename(filename)}\"")
                    append(HttpHeaders.ContentType, type)
                })
                append("file_id", fileId)
                if (endpoint != null) append("endpoint", endpoint)
                if (model != null) append("model", model)
                if (agentId != null) append("agent_id", agentId)
                if (toolResource != null) append("tool_resource", toolResource)
                if (messageFile != null) append("message_file", messageFile.toString())
                if (width != null) append("width", width.toString())
                if (height != null) append("height", height.toString())
            },
        )

        val response: FileObject = client.post {
            url { path("api/files") }
            // Match the official web client, which sets no request timeout on uploads (axios
            // default 0 = unlimited); the only interruption there is the user's cancel. The
            // client-wide 30s requestTimeoutMillis bounds the *entire* request, which a large image
            // on a slow link can exceed mid-transfer — and POSTs are not retried. Disable the
            // whole-request cap for uploads; socketTimeoutMillis (120s) still aborts a genuinely
            // stalled connection where no bytes are moving, so this can't hang forever.
            timeout {
                requestTimeoutMillis = Long.MAX_VALUE
            }
            setBody(multipart)
            if (onProgress != null) {
                var lastPct = -1
                var lastSent = -1L
                onUpload { sent, total ->
                    if (total == null || total <= 0L) return@onUpload
                    // Detect HttpRequestRetry replays: byte counter resets to 0.
                    if (sent < lastSent) lastPct = -1
                    lastSent = sent
                    val pct = ((sent * 100L) / total).toInt().coerceIn(0, 100)
                    if (pct != lastPct) {
                        lastPct = pct
                        try {
                            onProgress(pct / 100f)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Logger.w("FilesApi", e) { "onProgress callback threw; suppressing to keep upload alive" }
                        }
                    }
                }
            }
        }.body()

        Logger.d("FilesApi") {
            "uploadFile success: fileId=${response.fileId}, filepath=${response.filepath}, " +
                "type=${response.type}, width=${response.width}, height=${response.height}"
        }
        return response
    }

    suspend fun deleteFiles(request: DeleteFilesRequest) {
        client.delete {
            url { path("api/files") }
            setBody(request)
        }
    }

    suspend fun downloadFile(userId: String, fileId: String): ByteArray =
        client.get {
            url { path("api/files/download/$userId/$fileId") }
        }.body()

    /**
     * Requests a direct/presigned download URL (S3/CloudFront) for [fileId].
     * Throws on 501 (source has no direct-URL strategy, e.g. local storage) and
     * 400 (OpenAI-storage file missing a model) — the repository catches those
     * and falls back to the [downloadFile] proxy.
     */
    suspend fun getDownloadUrl(userId: String, fileId: String): FileDownloadURLResponse =
        client.get {
            url { path("api/files/download-url/$userId/$fileId") }
        }.body()

    /**
     * Fetches raw bytes from an absolute CDN [url] returned by [getDownloadUrl],
     * bypassing the LibreChat proxy. The URL is presigned, so no auth is needed.
     */
    suspend fun downloadFromUrl(url: String): ByteArray =
        client.get(url).body()

    /**
     * Fetches the deferred office-doc preview lifecycle for [fileId]. Poll while
     * `status == "pending"`; `ready`/`failed` are terminal.
     */
    suspend fun getFilePreview(fileId: String): FilePreviewResponse =
        client.get {
            url { path("api/files/$fileId/preview") }
        }.body()

    companion object {
        private fun encodeFilename(filename: String): String =
            filename.replace("\"", "\\\"")
    }
}
