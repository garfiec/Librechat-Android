package com.garfiec.librechat.core.data.repository

import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.model.UserFavorite

interface FavoritesRepository {
    suspend fun getFavorites(): Result<List<UserFavorite>>

    /** Replaces the server-side favorites list with [favorites]. */
    suspend fun setFavorites(favorites: List<UserFavorite>): Result<List<UserFavorite>>
}
