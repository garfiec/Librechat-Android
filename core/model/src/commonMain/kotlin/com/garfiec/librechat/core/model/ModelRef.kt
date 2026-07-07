package com.garfiec.librechat.core.model

import kotlinx.serialization.Serializable

/**
 * A concrete endpoint + model pair the user can start a chat on. Used by the most-used-models
 * ranking that backs the home-screen app shortcuts (Android) / quick actions (iOS).
 */
@Serializable
data class ModelRef(
    val endpoint: String,
    val model: String,
)
