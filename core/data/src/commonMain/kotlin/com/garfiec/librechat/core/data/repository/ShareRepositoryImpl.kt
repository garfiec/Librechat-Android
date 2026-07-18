package com.garfiec.librechat.core.data.repository

import com.garfiec.librechat.core.common.BackendVersion
import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.common.result.safeApiCall
import com.garfiec.librechat.core.model.SharedLink
import com.garfiec.librechat.core.model.response.SharedLinksResponse
import com.garfiec.librechat.core.network.api.ShareApi
import com.garfiec.librechat.core.network.client.ServerUrlProvider

class ShareRepositoryImpl(
    private val shareApi: ShareApi,
    private val serverUrlProvider: ServerUrlProvider,
    private val configRepository: ConfigRepository,
) : ShareRepository {

    /**
     * Backends older than 0.8.7 filter `GET /api/share` on the `isPublic` query param and default
     * it to false when absent, so the Shared Links screen returns empty unless we send `isPublic=true`.
     * 0.8.7+ dropped that filter; the param is ignored there (verified), so it's harmless to send.
     *
     * Fail-safe on an unknown version: when the backend version hasn't resolved yet (cold start,
     * immediately post-login, or detection failure) we send `isPublic=true` so a legacy server still
     * returns links. The param is omitted only when the backend is *confirmed* >= 0.8.7. Returns null
     * to omit, which threads straight into [ShareApi.getSharedLinks].
     */
    private fun isPublicFilter(): Boolean? {
        val version = configRepository.detectedBackendVersion.value
        val isConfirmedModern = version != null && BackendVersion.isCompatibleOrNewer(version, "0.8.7")
        return if (isConfirmedModern) null else true
    }

    override suspend fun createShareLink(conversationId: String): Result<String> {
        return safeApiCall {
            val sharedLink = shareApi.createShareLink(conversationId)
            val shareId = sharedLink.shareId
                ?: throw IllegalStateException("Server returned no shareId")
            val baseUrl = serverUrlProvider.getBaseUrl().trimEnd('/')
            "$baseUrl/share/$shareId"
        }
    }

    override suspend fun getSharedLinksPaginated(cursor: String?): Result<SharedLinksResponse> {
        return safeApiCall {
            shareApi.getSharedLinks(cursor = cursor, isPublic = isPublicFilter())
        }
    }

    override suspend fun toggleShareVisibility(shareId: String): Result<SharedLink> {
        return safeApiCall {
            shareApi.toggleShareVisibility(shareId)
        }
    }

    override suspend fun deleteShareLink(shareId: String): Result<Unit> {
        return safeApiCall {
            shareApi.deleteShareLink(shareId)
        }
    }
}
