package com.garfiec.librechat.core.model.request

import kotlinx.serialization.Serializable

/**
 * Body for `POST /api/endpoints/context-projection` (v0.8.7): "what context would
 * the next call send for this branch under this config", computed server-side
 * without invoking the model. [messageId] is the viewed branch's tail.
 * Mirrors upstream `TContextProjectionRequest`.
 */
@Serializable
data class ContextProjectionRequest(
    val conversationId: String,
    val messageId: String,
    val endpoint: String,
    val model: String? = null,
    val agentId: String? = null,
    val spec: String? = null,
    val maxContextTokens: Int? = null,
    val calibrationRatio: Double? = null,
)
