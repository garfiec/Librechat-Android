package com.garfiec.librechat.core.model.request

import kotlinx.serialization.Serializable

@Serializable
data class ConvoPinBody(
    val arg: ConvoPinArg,
)

@Serializable
data class ConvoPinArg(
    val conversationId: String,
    val pinned: Boolean,
)
