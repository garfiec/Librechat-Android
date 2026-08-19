package com.garfiec.librechat.core.data.repository

import co.touchlab.kermit.Logger
import com.garfiec.librechat.core.common.BackendVersion
import com.garfiec.librechat.core.common.result.ApiException
import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.common.result.safeApiCall
import com.garfiec.librechat.core.model.ToolFavorite
import com.garfiec.librechat.core.model.ToolFavoriteItemType
import com.garfiec.librechat.core.model.ToolFavoritesLimits
import com.garfiec.librechat.core.network.api.FavoritesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ToolFavoritesRepositoryImpl(
    private val favoritesApi: FavoritesApi,
    private val configRepository: ConfigRepository,
) : ToolFavoritesRepository {

    private val _favorites = MutableStateFlow<Set<ToolFavorite>>(emptySet())
    override val favorites: StateFlow<Set<ToolFavorite>> = _favorites.asStateFlow()

    private val _isSupported = MutableStateFlow(false)
    override val isSupported: StateFlow<Boolean> = _isSupported.asStateFlow()

    /** Serializes toggles so two rapid stars can't roll each other back. */
    private val writeMutex = Mutex()

    /**
     * Set once a probe has 404'd, so the picker does not re-ask a server that has already
     * answered. [_isSupported] cannot serve this: it is also false before the first probe and
     * after a [clear], and re-probing on every picker open is exactly what it would cause.
     * Reset by [clear] on account/server switch.
     */
    private var routeMissingByProbe = false

    /**
     * True only when the routes are KNOWN absent: a build commit that resolved to a tag below
     * v0.8.8-rc1 (#13952), or a probe that already 404'd.
     *
     * A server the version gate cannot place is NOT ruled out: support here is discoverable by
     * asking — one GET, no rate limiter, a 404 that means exactly one thing — so a dev build
     * reporting the previous release, or a server built past this app's commit-map pin, gets
     * asked instead of assumed. Assuming absence there renders the picker with no star column at
     * all on the self-hosted servers most likely to have the routes.
     */
    private fun favoritesRuledOut(): Boolean =
        routeMissingByProbe ||
            BackendVersion.featureSupport(
                configRepository.detectedBackend.value,
                minVersion = "0.8.8-rc1",
            ).isRuledOut

    override suspend fun refresh(): Result<Set<ToolFavorite>> {
        if (favoritesRuledOut()) {
            _isSupported.value = false
            _favorites.value = emptySet()
            return Result.Success(emptySet())
        }
        return when (val result = safeApiCall { favoritesApi.getToolFavorites() }) {
            is Result.Success -> {
                _favorites.value = result.data.toSet()
                _isSupported.value = true
                Result.Success(result.data.toSet())
            }

            is Result.Error -> {
                // A server without the routes is the expected case on every pre-0.8.8 install,
                // not a failure to report: probing is how support is discovered, so the miss
                // just turns pinning off. Anything else leaves support as-is — a transient 500
                // must not permanently hide a feature the server does have.
                if ((result.exception as? ApiException)?.statusCode == HTTP_NOT_FOUND) {
                    _isSupported.value = false
                    _favorites.value = emptySet()
                    routeMissingByProbe = true
                    Logger.d { "Tool favorites unsupported on this server" }
                }
                result
            }

            is Result.Loading -> Result.Loading
        }
    }

    override suspend fun toggle(
        itemType: ToolFavoriteItemType,
        itemId: String,
    ): Result<Boolean> {
        if (itemId.isBlank() || itemId.length > ToolFavoritesLimits.MAX_ITEM_ID_LENGTH) {
            return Result.Error(
                message = "That item cannot be pinned (id longer than " +
                    "${ToolFavoritesLimits.MAX_ITEM_ID_LENGTH} characters).",
            )
        }

        return writeMutex.withLock {
            val favorite = ToolFavorite(itemType, itemId)
            val previous = _favorites.value
            val isPinned = favorite in previous
            if (!isPinned && previous.size >= ToolFavoritesLimits.MAX_TOOL_FAVORITES) {
                // Short-circuit the cap the server enforces, so the star never flickers on and
                // back off with a raw MAX_FAVORITES_EXCEEDED body behind it.
                return@withLock Result.Error(
                    message = "You have pinned the maximum of " +
                        "${ToolFavoritesLimits.MAX_TOOL_FAVORITES} items.",
                )
            }

            _favorites.value = if (isPinned) previous - favorite else previous + favorite

            val result = safeApiCall {
                if (isPinned) {
                    favoritesApi.removeToolFavorite(itemType, itemId)
                } else {
                    favoritesApi.addToolFavorite(itemType, itemId)
                }
            }
            when (result) {
                is Result.Success -> Result.Success(!isPinned)
                is Result.Error -> {
                    _favorites.value = previous
                    result
                }

                is Result.Loading -> Result.Loading
            }
        }
    }

    override fun clear() {
        _favorites.value = emptySet()
        _isSupported.value = false
        // The verdict belonged to the server being left; keeping it carries one server's 404 onto
        // the next account.
        routeMissingByProbe = false
    }

    private companion object {
        const val HTTP_NOT_FOUND = 404
    }
}
