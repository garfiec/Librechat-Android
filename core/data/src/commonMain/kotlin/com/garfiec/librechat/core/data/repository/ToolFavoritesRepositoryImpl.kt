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

    override suspend fun refresh(): Result<Set<ToolFavorite>> {
        // The routes landed on the untagged 0.8.8 line (#13952), so the version alone cannot
        // decide: a dev build carrying them still reports 0.8.7. The date fallback covers that.
        // Landing day itself rather than the day after — a same-day predecessor misread as
        // having the routes costs one 404 that the branch below turns into "unsupported",
        // whereas rounding up would hide the feature from a day's worth of real 0.8.8 servers.
        if (!BackendVersion.supportsFeature(
                configRepository.detectedBackend.value,
                minVersion = "0.8.8-rc1",
                landedDate = "2026-07-05",
            )
        ) {
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
    }

    private companion object {
        const val HTTP_NOT_FOUND = 404
    }
}
