package com.garfiec.librechat.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A file bundled inside a skill (upstream `TSkillFile`). Returned by the flat
 * skill-file endpoints. [content]/[isBinary] are populated only on the
 * single-file content fetch, not in list responses; most fields are optional
 * for forward-compat (`ignoreUnknownKeys`).
 */
@Serializable
data class SkillFile(
    @SerialName("_id") val id: String? = null,
    val skillId: String? = null,
    val relativePath: String,
    @SerialName("file_id") val fileId: String? = null,
    val filename: String? = null,
    val filepath: String? = null,
    val source: String? = null,
    val mimeType: String? = null,
    val bytes: Long = 0,
    val category: String? = null,
    val isExecutable: Boolean = false,
    val content: String? = null,
    val isBinary: Boolean? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
)
