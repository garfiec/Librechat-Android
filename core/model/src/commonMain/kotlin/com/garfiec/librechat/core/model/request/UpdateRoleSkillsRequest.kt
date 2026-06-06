package com.garfiec.librechat.core.model.request

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Request body for `PUT /api/roles/:roleName/skills` (upstream partial
 * `skillPermissionsSchema`) — the role's SKILLS permission booleans. All fields
 * optional; the server merges this partial onto the role's existing SKILLS
 * permissions. No `arg`-wrap. Admin-only (manageRoles).
 */
@Serializable
data class UpdateRoleSkillsRequest(
    @SerialName("USE") val use: Boolean? = null,
    @SerialName("CREATE") val create: Boolean? = null,
    @SerialName("SHARE") val share: Boolean? = null,
    @SerialName("SHARE_PUBLIC") val sharePublic: Boolean? = null,
)
