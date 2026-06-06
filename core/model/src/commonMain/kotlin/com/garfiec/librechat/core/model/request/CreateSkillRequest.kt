package com.garfiec.librechat.core.model.request

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Request body for `POST /api/skills` (upstream `TCreateSkill`). No `arg`-wrap.
 * `invocationMode` is deliberately omitted — it's UI-only/deprecated upstream
 * and not persisted. [frontmatter] is the open structured bag (optional).
 */
@Serializable
data class CreateSkillRequest(
    val name: String,
    val description: String,
    val body: String = "",
    val displayTitle: String? = null,
    val category: String? = null,
    val frontmatter: JsonObject? = null,
    val alwaysApply: Boolean? = null,
)
