package com.garfiec.librechat.feature.agents.viewmodel.delegate

import co.touchlab.kermit.Logger
import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.data.repository.AgentRepository
import com.garfiec.librechat.core.data.repository.FileRepository
import com.garfiec.librechat.core.model.AgentFile
import com.garfiec.librechat.core.model.FileObject
import com.garfiec.librechat.core.model.request.DeleteFileEntry
import com.garfiec.librechat.feature.agents.util.ContentReader
import com.garfiec.librechat.feature.agents.viewmodel.AgentEditorStateHandle
import com.garfiec.librechat.feature.agents.viewmodel.AgentEditorViewModel
import com.garfiec.librechat.feature.agents.viewmodel.AgentFileSlot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns the agent editor's file concerns: the per-capability attachment slots
 * (code / knowledge / context), their upload + removal against
 * `POST/DELETE /api/files` with the `tool_resource` routing, the avatar
 * upload/reset, and the lazy enrichment of slot file_ids with
 * filename/bytes/type from `GET /api/files/agent/:id`.
 *
 * The enrichment cache ([loadedAgentFileObjects]) is held here so whichever of
 * the agent load / file load finishes second can trigger the merge against the
 * by-then-populated slots — the loader calls [remergeLoadedFiles] after applying
 * agent data, and the revert path calls [resetFileCache] before reloading.
 */
class AgentFilesDelegate(
    private val stateHandle: AgentEditorStateHandle,
    private val agentRepository: AgentRepository,
    private val fileRepository: FileRepository,
    private val contentReader: ContentReader,
    private val ioDispatcher: CoroutineDispatcher,
) {

    /** Cached file-detail payload from `GET /api/files/agent/:id`. Held here so
     *  that whichever of loadAgent / [loadAgentFiles] finishes second can
     *  trigger the merge against the by-then-populated tool_resources slots.
     *  Without this cache, [loadAgentFiles] finishing first would read empty
     *  slot lists and produce a no-op enrichment. */
    private var loadedAgentFileObjects: List<FileObject>? = null

    /**
     * Fetches `GET /api/files/agent/:id` and merges filename/bytes/type into
     * the per-capability slots populated from `tool_resources.<X>.file_ids`.
     * The agent payload only carries file_ids — without this call, chips
     * would render with bare IDs.
     */
    fun loadAgentFiles(agentId: String) {
        stateHandle.scope.launch {
            when (val result = fileRepository.getAgentFiles(agentId)) {
                is Result.Success -> {
                    loadedAgentFileObjects = result.data
                    mergeAgentFileMetadata(result.data)
                }
                is Result.Error -> { /* Best-effort enrichment; chips fall back to fileId */ }
                is Result.Loading -> { /* no-op */ }
            }
        }
    }

    /** Re-runs enrichment if the file payload already arrived. Called by the
     *  loader after agent data populates the per-capability slot lists; without
     *  it, an earlier-finishing files request would have merged against empty
     *  lists and produced no enrichment. */
    fun remergeLoadedFiles() {
        loadedAgentFileObjects?.let { mergeAgentFileMetadata(it) }
    }

    /** Drops the enrichment cache so a revert re-fetches a fresh file set. */
    fun resetFileCache() {
        loadedAgentFileObjects = null
    }

    private fun mergeAgentFileMetadata(files: List<FileObject>) {
        val byId = files.associateBy { it.fileId }
        fun enrich(list: List<AgentFile>): List<AgentFile> = list.map { agentFile ->
            byId[agentFile.fileId]?.let { obj ->
                agentFile.copy(
                    filename = agentFile.filename ?: obj.filename,
                    bytes = agentFile.bytes ?: obj.bytes,
                    type = agentFile.type ?: obj.type,
                )
            } ?: agentFile
        }
        stateHandle.update {
            copy(
                codeFiles = enrich(codeFiles),
                knowledgeFiles = enrich(knowledgeFiles),
                contextFiles = enrich(contextFiles),
            )
        }
    }

    /**
     * Picks a slot's current list. Used by upload/remove so the caller doesn't
     * have to switch on the enum.
     */
    private fun filesFor(slot: AgentFileSlot): List<AgentFile> = when (slot) {
        AgentFileSlot.CODE -> stateHandle.state.codeFiles
        AgentFileSlot.KNOWLEDGE -> stateHandle.state.knowledgeFiles
        AgentFileSlot.CONTEXT -> stateHandle.state.contextFiles
    }

    private fun setFilesFor(slot: AgentFileSlot, files: List<AgentFile>) {
        stateHandle.update {
            when (slot) {
                AgentFileSlot.CODE -> copy(codeFiles = files)
                AgentFileSlot.KNOWLEDGE -> copy(knowledgeFiles = files)
                AgentFileSlot.CONTEXT -> copy(contextFiles = files)
            }
        }
    }

    /**
     * Upload a file for the given capability slot. The backend attaches the
     * file to `tool_resources.<wire>.file_ids` on the agent when both
     * `agent_id` + `tool_resource` are supplied. New (unsaved) agents can't
     * accept files yet — the user is told to save first via a snackbar.
     */
    fun uploadAgentFile(fileRef: Any, slot: AgentFileSlot) {
        val agentId = stateHandle.state.agentId
        if (agentId.isNullOrBlank()) {
            stateHandle.update {
                copy(error = AgentEditorViewModel.AGENT_FILES_SAVE_FIRST_MARKER)
            }
            return
        }
        stateHandle.scope.launch {
            try {
                // Reading bytes off the URI is blocking I/O — keep it off the Main
                // dispatcher (viewModelScope = Main.immediate) to avoid an ANR on
                // large files. Mirrors [uploadAvatar].
                val bytes = withContext(ioDispatcher) { contentReader.readBytes(fileRef) } ?: run {
                    stateHandle.update { copy(error = "Could not read file") }
                    return@launch
                }
                if (bytes.size > AGENT_FILE_SIZE_LIMIT_BYTES) {
                    val limitMb = (AGENT_FILE_SIZE_LIMIT_BYTES / (1024 * 1024)).toInt()
                    stateHandle.update {
                        copy(error = "${AgentEditorViewModel.AGENT_FILES_TOO_LARGE_MARKER}$limitMb")
                    }
                    return@launch
                }
                val filename = contentReader.getFileName(fileRef) ?: "upload"
                val mimeType = contentReader.getMimeType(fileRef) ?: "application/octet-stream"

                stateHandle.update { copy(uploadingSlots = uploadingSlots + slot) }
                val result = fileRepository.uploadFile(
                    bytes = bytes,
                    filename = filename,
                    type = mimeType,
                    endpoint = "agents",
                    agentId = agentId,
                    toolResource = slot.wire,
                )
                stateHandle.update { copy(uploadingSlots = uploadingSlots - slot) }
                when (result) {
                    is Result.Success -> {
                        val obj = result.data
                        val agentFile = AgentFile(
                            fileId = obj.fileId,
                            filename = obj.filename,
                            bytes = obj.bytes,
                            type = obj.type,
                            originResource = slot.wire,
                        )
                        // Re-pick latest because the snackbar might have cleared
                        // state between the upload start and completion.
                        setFilesFor(slot, filesFor(slot) + agentFile)
                    }
                    is Result.Error -> {
                        stateHandle.update {
                            copy(error = result.message ?: AgentEditorViewModel.AGENT_FILE_UPLOAD_FAILED_MARKER)
                        }
                    }
                    is Result.Loading -> { /* no-op */ }
                }
            } catch (e: Exception) {
                Logger.e(e) { "uploadAgentFile: unexpected error" }
                stateHandle.update {
                    copy(
                        uploadingSlots = uploadingSlots - slot,
                        error = AgentEditorViewModel.AGENT_FILE_UPLOAD_FAILED_MARKER,
                    )
                }
            }
        }
    }

    fun removeAgentFile(fileId: String, slot: AgentFileSlot) {
        val agentId = stateHandle.state.agentId
        // Local-only state when there's no agentId yet (we never let this happen
        // through uploadAgentFile, but stay defensive).
        if (agentId.isNullOrBlank()) {
            setFilesFor(slot, filesFor(slot).filterNot { it.fileId == fileId })
            return
        }
        // Optimistically remove; rollback on error.
        val before = filesFor(slot)
        setFilesFor(slot, before.filterNot { it.fileId == fileId })
        stateHandle.scope.launch {
            val target = before.firstOrNull { it.fileId == fileId } ?: return@launch
            // The Context slot UI shows files from both `context` and `ocr`
            // tool_resources merged. Route the deletion to the slot the file
            // was actually loaded from so the backend can find and remove
            // the file_id; falling back to slot.wire for files uploaded in
            // this session (which the picker writes under slot.wire).
            val toolResource = target.originResource ?: slot.wire
            val result = fileRepository.deleteFiles(
                files = listOf(DeleteFileEntry(fileId = target.fileId, filepath = "")),
                agentId = agentId,
                toolResource = toolResource,
            )
            if (result is Result.Error) {
                // Rollback
                setFilesFor(slot, before)
                stateHandle.update {
                    copy(error = result.message ?: AgentEditorViewModel.AGENT_FILE_REMOVE_FAILED_MARKER)
                }
            }
        }
    }

    fun uploadAvatar(uri: Any) {
        val agentId = stateHandle.state.agentId ?: return
        stateHandle.scope.launch {
            try {
                // Reading bytes off the URI is blocking I/O — keep it off the Main
                // dispatcher (viewModelScope = Main.immediate) to avoid an ANR on
                // large images. Mirrors the FileAttachmentDelegate fix.
                val bytes = withContext(ioDispatcher) { contentReader.readBytes(uri) } ?: return@launch
                if (bytes.size > AVATAR_SIZE_LIMIT_BYTES) {
                    val limitMb = AVATAR_SIZE_LIMIT_BYTES / (1024 * 1024)
                    stateHandle.update { copy(error = "Avatar must be ${limitMb}MB or smaller") }
                    return@launch
                }
                val mimeType = contentReader.getMimeType(uri) ?: "image/png"

                when (val result = agentRepository.uploadAgentAvatar(agentId, bytes, mimeType)) {
                    is Result.Success -> {
                        stateHandle.update { copy(avatarUrl = result.data.avatarUrl) }
                    }
                    is Result.Error -> {
                        stateHandle.update { copy(error = result.message ?: "Failed to upload avatar") }
                    }
                    is Result.Loading -> { /* no-op */ }
                }
            } catch (e: CancellationException) {
                // Cooperative cancellation must propagate (SKIE/iOS requirement).
                throw e
            } catch (e: Exception) {
                stateHandle.update { copy(error = "Failed to read image: ${e.message}") }
            }
        }
    }

    fun resetAvatar() {
        val agentId = stateHandle.state.agentId ?: return
        stateHandle.scope.launch {
            when (val result = agentRepository.resetAgentAvatar(agentId)) {
                is Result.Success -> {
                    stateHandle.update { copy(avatarUrl = null) }
                }
                is Result.Error -> {
                    stateHandle.update { copy(error = result.message ?: "Failed to reset avatar") }
                }
                is Result.Loading -> { /* no-op */ }
            }
        }
    }

    private companion object {
        /**
         * Avatar size cap. Upstream default in fileConfig.avatarSizeLimit is 2MB
         * (packages/data-provider/src/file-config.ts:430). Mobile StartupConfig
         * doesn't surface fileConfig yet, so this hardcodes the default.
         */
        const val AVATAR_SIZE_LIMIT_BYTES = 2 * 1024 * 1024L

        /**
         * Per-file cap for agent attachments. Upstream's default for the agents
         * endpoint is 512MB (packages/data-provider/src/file-config.ts:399).
         */
        const val AGENT_FILE_SIZE_LIMIT_BYTES = 512L * 1024 * 1024
    }
}
