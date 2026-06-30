package com.garfiec.librechat.core.model.response

import com.garfiec.librechat.core.model.ChatProject
import kotlinx.serialization.Serializable

@Serializable
data class ProjectListResponse(
    val projects: List<ChatProject> = emptyList(),
    val nextCursor: String? = null,
)
