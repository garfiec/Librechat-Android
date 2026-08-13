package com.garfiec.librechat.core.model.request

import kotlinx.serialization.Serializable

/**
 * `POST /api/prompts/groups/{groupId}/prompts` body.
 *
 * The version's fields nest under `prompt`: the route reads `req.body.prompt.prompt`, so a flat
 * body is rejected with HTTP 400. The group comes from the path — the route overwrites any
 * `groupId` the body carries. See `feature/chat/CLAUDE.md`.
 */
@Serializable
data class AddPromptToGroupRequest(
    val prompt: CreatePromptData,
)
