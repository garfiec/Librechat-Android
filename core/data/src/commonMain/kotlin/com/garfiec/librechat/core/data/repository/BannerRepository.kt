package com.garfiec.librechat.core.data.repository

import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.model.Banner

/** Fetches the server-configured banner for display in the app. */
interface BannerRepository {
    /** Returns the active banner, or null when the server has none configured. */
    suspend fun getBanner(): Result<Banner?>
}
