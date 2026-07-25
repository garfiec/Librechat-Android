package com.garfiec.librechat.core.model.request

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Body of `PATCH /api/memories/preferences`. The server reads `memories` and 400s on any other
 * shape, so the wire name is pinned rather than taken from the Kotlin property.
 */
@Serializable
data class UpdateMemoryPreferencesRequest(
    @SerialName("memories") val enabled: Boolean,
)
