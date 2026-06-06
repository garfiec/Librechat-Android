package com.garfiec.librechat.core.model.request

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Request body for `PATCH /api/skills/:id` (upstream `TUpdateSkillPayload` +
 * the required [expectedVersion]). All content fields are optional (partial
 * update). [expectedVersion] is MANDATORY (int ≥ 1) — omitting it 400s, and a
 * stale value 409s with `skill_version_conflict`. Send the version of the
 * skill that was loaded into the editor. No `arg`-wrap.
 */
@Serializable
data class UpdateSkillRequest(
    val expectedVersion: Int,
    val name: String? = null,
    val description: String? = null,
    val body: String? = null,
    val displayTitle: String? = null,
    val category: String? = null,
    val frontmatter: JsonObject? = null,
    val alwaysApply: Boolean? = null,
)
