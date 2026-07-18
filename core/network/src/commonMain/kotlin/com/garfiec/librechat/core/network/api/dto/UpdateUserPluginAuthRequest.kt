package com.garfiec.librechat.core.network.api.dto

import kotlinx.serialization.Serializable

/**
 * Rich `POST /api/user/plugins` body used by the tool-auth dialog (upstream
 * controllers/UserController.js:167). The controller switches on field presence.
 */
@Serializable
data class UpdateUserPluginAuthRequest(
    val pluginKey: String,
    val action: String,
    val auth: Map<String, String?>? = null,
    val isEntityTool: Boolean? = null,
)
