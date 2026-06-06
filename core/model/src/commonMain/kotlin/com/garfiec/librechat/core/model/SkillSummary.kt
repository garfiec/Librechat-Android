package com.garfiec.librechat.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Summary view of a Skill (upstream `TSkillSummary` = `TSkill` minus body &
 * frontmatter). Returned by `GET /api/skills`. The agent-editor skills selector
 * only needs [id] (stored in `agent.skills`) and [name] / [displayTitle] to
 * render chips; the rest are optional for nicer UI and round-trip safety. The
 * server may add fields at any time — `ignoreUnknownKeys` covers that.
 */
@Serializable
data class SkillSummary(
    @SerialName("_id") val id: String,
    val name: String,
    @SerialName("displayTitle") val displayTitle: String? = null,
    val description: String? = null,
    val category: String? = null,
)
