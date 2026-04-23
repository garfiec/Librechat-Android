package com.garfiec.librechat.core.network.api

import com.garfiec.librechat.core.model.UserFavorite
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.path
import kotlinx.serialization.Serializable

@Serializable
private data class FavoritesRequest(val favorites: List<UserFavorite>)

/**
 * User-pinned favorites (agents + model/endpoint combinations).
 * Introduced in upstream v0.8.5 via `api/server/controllers/FavoritesController.js`.
 */
class FavoritesApi(
    private val client: HttpClient,
) {
    suspend fun getFavorites(): List<UserFavorite> =
        client.get {
            url { path("api/user/settings/favorites") }
        }.body()

    /**
     * Replaces the user's entire favorites list server-side (upsert-by-overwrite).
     * The response echoes the stored list.
     */
    suspend fun updateFavorites(favorites: List<UserFavorite>): List<UserFavorite> =
        client.post {
            url { path("api/user/settings/favorites") }
            setBody(FavoritesRequest(favorites))
        }.body()
}
