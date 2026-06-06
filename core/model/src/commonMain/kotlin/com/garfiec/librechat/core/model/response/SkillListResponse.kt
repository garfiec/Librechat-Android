package com.garfiec.librechat.core.model.response

import com.garfiec.librechat.core.model.SkillSummary
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Response of `GET /api/skills` (upstream `TSkillListResponse`). [after] is the
 * cursor for the next page when [hasMore] is true.
 */
@Serializable
data class SkillListResponse(
    val skills: List<SkillSummary> = emptyList(),
    @SerialName("has_more") val hasMore: Boolean = false,
    val after: String? = null,
)
