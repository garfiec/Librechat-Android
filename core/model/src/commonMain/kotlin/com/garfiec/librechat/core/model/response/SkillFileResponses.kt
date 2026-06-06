package com.garfiec.librechat.core.model.response

import com.garfiec.librechat.core.model.SkillFile
import kotlinx.serialization.Serializable

/** `GET /api/skills/:id/files` → `{ files: TSkillFile[] }`. */
@Serializable
data class SkillFileListResponse(
    val files: List<SkillFile> = emptyList(),
)

/**
 * `GET /api/skills/:id/files/:relativePath` JSON mode (upstream
 * `TSkillFileContentResponse`). [content] is absent for binary files (which
 * reach the endpoint with [isBinary] = true and are download-only).
 */
@Serializable
data class SkillFileContentResponse(
    val content: String? = null,
    val mimeType: String? = null,
    val isBinary: Boolean = false,
    val relativePath: String,
    val filename: String? = null,
    val bytes: Long = 0,
)

/** `DELETE /api/skills/:id/files/:relativePath` → `{ skillId, relativePath, deleted }`. */
@Serializable
data class DeleteSkillFileResponse(
    val skillId: String? = null,
    val relativePath: String,
    val deleted: Boolean = false,
)
