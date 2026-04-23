package com.garfiec.librechat.core.data.repository

import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.common.result.safeApiCall
import com.garfiec.librechat.core.model.FavoritesLimits
import com.garfiec.librechat.core.model.UserFavorite
import com.garfiec.librechat.core.network.api.FavoritesApi

class FavoritesRepositoryImpl(
    private val favoritesApi: FavoritesApi,
) : FavoritesRepository {

    override suspend fun getFavorites(): Result<List<UserFavorite>> {
        return safeApiCall {
            favoritesApi.getFavorites()
        }
    }

    override suspend fun setFavorites(favorites: List<UserFavorite>): Result<List<UserFavorite>> {
        if (favorites.size > FavoritesLimits.MAX_FAVORITES) {
            return Result.Error(
                message = "Maximum ${FavoritesLimits.MAX_FAVORITES} favorites allowed.",
            )
        }
        for (fav in favorites) {
            val fields = listOf(fav.agentId, fav.model, fav.endpoint, fav.spec)
            if (fields.any { (it?.length ?: 0) > FavoritesLimits.MAX_STRING_LENGTH }) {
                return Result.Error(
                    message = "A favorite field exceeds the ${FavoritesLimits.MAX_STRING_LENGTH} character limit.",
                )
            }
        }
        return safeApiCall {
            favoritesApi.updateFavorites(favorites)
        }
    }
}
