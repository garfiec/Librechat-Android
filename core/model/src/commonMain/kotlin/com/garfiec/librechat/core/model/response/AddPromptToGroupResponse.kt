package com.garfiec.librechat.core.model.response

import com.garfiec.librechat.core.model.Prompt
import kotlinx.serialization.Serializable

/**
 * `POST /api/prompts/groups/{groupId}/prompts` response — the new version wrapped under `prompt`,
 * not a bare [Prompt].
 *
 * [prompt] is deliberately non-nullable: the route answers `{ "message": "Error saving prompt" }`
 * with HTTP 200 when the write fails, so a nullable field would decode that as a success carrying
 * nothing, and the caller promotes to production on the strength of it.
 */
@Serializable
data class AddPromptToGroupResponse(
    val prompt: Prompt,
)
