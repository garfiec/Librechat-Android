package com.garfiec.librechat.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class User(
    val id: String? = null,
    @SerialName("_id") val mongoId: String? = null,
    val name: String? = null,
    val username: String? = null,
    val email: String,
    val emailVerified: Boolean = false,
    val avatar: String? = null,
    val provider: String = "local",
    val role: String = "USER",
    val twoFactorEnabled: Boolean = false,
    val termsAccepted: Boolean = false,
    /** ISO-8601 timestamp of terms acceptance, or null if never accepted. */
    val termsAcceptedAt: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    /** Per-user opt-outs stored on the profile; absent on older servers. */
    val personalization: UserPersonalization? = null,
)

/**
 * The user's own personalization opt-outs, orthogonal to the server-side role permissions:
 * a user who *may* use memories can still switch them off for themselves here.
 */
@Serializable
data class UserPersonalization(
    /** Server default is true; false means the user opted out of memory entirely. */
    val memories: Boolean = true,
)
