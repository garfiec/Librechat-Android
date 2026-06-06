package com.garfiec.librechat.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Full skill record (upstream `TSkill`, `serializeSkill`). Returned by
 * `GET /api/skills/:id` and as the body of POST/PATCH responses. [body] is the
 * markdown content shown in the detail view; [frontmatter] is the structured
 * YAML bag (open `Record`) modeled as an opaque [JsonObject] per the model
 * convention. Most fields are optional/forward-compat — the server may add
 * keys at any time (`ignoreUnknownKeys`).
 */
@Serializable
data class Skill(
    @SerialName("_id") val id: String,
    val name: String,
    val displayTitle: String? = null,
    val description: String = "",
    val body: String = "",
    val frontmatter: JsonObject? = null,
    val category: String? = null,
    val disableModelInvocation: Boolean? = null,
    val userInvocable: Boolean? = null,
    val allowedTools: List<String>? = null,
    val author: String? = null,
    val authorName: String? = null,
    val version: Int = 1,
    val source: String? = null,
    val fileCount: Int = 0,
    val alwaysApply: Boolean? = null,
    val isPublic: Boolean? = null,
    val tenantId: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    /** Non-blocking coaching hints; present only on POST/PATCH responses. */
    val warnings: List<SkillWarning>? = null,
)

/**
 * Non-blocking coaching hint riding on a 2xx create/update response (upstream
 * `TSkillWarning`) — e.g. "description too short, Claude may undertrigger".
 */
@Serializable
data class SkillWarning(
    val field: String = "",
    val code: String = "",
    val message: String = "",
    val severity: String = "warning",
)
