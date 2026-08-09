package com.garfiec.librechat.core.data.repository

import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.common.result.safeApiCall
import com.garfiec.librechat.core.model.Banner
import com.garfiec.librechat.core.network.api.BannerApi

class BannerRepositoryImpl(
    private val bannerApi: BannerApi,
) : BannerRepository {

    override suspend fun getBanner(): Result<Banner?> =
        safeApiCall { bannerApi.getBanner() }
}
