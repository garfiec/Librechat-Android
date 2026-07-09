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
)
