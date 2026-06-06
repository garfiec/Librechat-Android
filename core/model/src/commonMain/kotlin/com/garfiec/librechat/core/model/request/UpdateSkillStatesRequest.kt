package com.garfiec.librechat.core.model.request

import kotlinx.serialization.Serializable

/**
 * Request body for `POST /api/user/settings/skills/active` (upstream
 * `{ skillStates: Record<skillId, boolean> }`). The response is the pruned
 * `Record<skillId, boolean>` map. No `arg`-wrap.
 */
@Serializable
data class UpdateSkillStatesRequest(
    val skillStates: Map<String, Boolean>,
)
