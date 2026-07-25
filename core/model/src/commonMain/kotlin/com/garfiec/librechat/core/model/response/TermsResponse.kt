package com.garfiec.librechat.core.model.response

import kotlinx.serialization.Serializable

@Serializable
data class TermsResponse(
    val termsOfService: String? = null,
    val privacyPolicy: String? = null,
    /** Whether this user has accepted the current terms. */
    val termsAccepted: Boolean = false,
    /** ISO-8601 timestamp of acceptance, or null if never accepted. */
    val termsAcceptedAt: String? = null,
)

/** Response of `POST /api/user/terms/accept`. */
@Serializable
data class TermsAcceptResponse(
    val message: String? = null,
    /** ISO-8601 timestamp the server recorded for this acceptance. */
    val termsAcceptedAt: String? = null,
)
