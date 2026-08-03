package com.garfiec.librechat.core.model.response

import com.garfiec.librechat.core.model.PromptGroup
import kotlinx.serialization.Serializable

/**
 * `POST /api/prompts` response.
 *
 * The route sends `createPromptGroup`'s return value verbatim, which wraps the new group alongside
 * the prompt itself — not a bare [PromptGroup] like the other prompt endpoints return. The nested
 * group additionally carries a `productionPrompt` the group document does not store, synthesized
 * from the prompt just created.
 *
 * The sibling `prompt` is deliberately not modelled. Nothing reads it, and `Prompt` has several
 * non-nullable fields, so decoding it would only add ways for a successful create to look like a
 * failure.
 */
@Serializable
data class CreatePromptResponse(
    val group: PromptGroup,
)
