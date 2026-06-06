package com.garfiec.librechat.core.model.response

import com.garfiec.librechat.core.model.Skill
import com.garfiec.librechat.core.model.SkillWarning
import kotlinx.serialization.Serializable

/**
 * Body of a `409 skill_version_conflict` from `PATCH /api/skills/:id` (upstream
 * `TSkillConflictResponse`). [current] is the authoritative server state the
 * editor should rebase onto before retrying — never blindly overwrite.
 */
@Serializable
data class SkillConflictResponse(
    val error: String,
    val current: Skill,
)

/** Response of `DELETE /api/skills/:id` (upstream `{ id, deleted: true }`). */
@Serializable
data class DeleteSkillResponse(
    val id: String,
    val deleted: Boolean = false,
)

/**
 * Body of a `400 Validation failed` from create/update (upstream
 * `{ error, issues: ValidationIssue[] }`). [issues] carries field-level
 * messages — e.g. reserved skill-name prefix/word violations the client's
 * kebab/length checks don't catch. `ValidationIssue` is shape-compatible with
 * [SkillWarning] (field/code/message/severity).
 */
@Serializable
data class SkillValidationErrorResponse(
    val error: String? = null,
    val issues: List<SkillWarning> = emptyList(),
)
