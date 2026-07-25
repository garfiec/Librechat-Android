package com.garfiec.librechat.feature.chat.viewmodel

import androidx.compose.runtime.Immutable
import com.garfiec.librechat.core.model.response.FileUploadConfig

/**
 * Per-account server-derived config: the signed-in user's display fields (for message avatars)
 * and the server upload config. Written only by [ChatViewModel] on config/user load.
 */
@Immutable
data class AccountConfigState(
    val userName: String? = null,
    val userAvatarUrl: String? = null,
    /**
     * Server upload config (`GET /api/files/config`).
     * Null before fetch or on fetch failure; treated as no constraint (controls fail open).
     */
    val fileUploadConfig: FileUploadConfig? = null,
    /**
     * The user switched memories off for themselves (`user.personalization.memories == false`).
     * Distinct from the MEMORIES role permission: this is the user's own opt-out and it hides the
     * composer memory toggle even for a role that may use memory. False until the profile loads,
     * and on servers with no `personalization` block (the server default is opted IN).
     */
    val memoriesOptedOut: Boolean = false,
)
