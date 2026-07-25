package com.garfiec.librechat.core.network.api

import com.garfiec.librechat.core.model.ToolFavorite
import com.garfiec.librechat.core.model.ToolFavoriteItemType
import com.garfiec.librechat.core.model.ToolFavoriteWire
import com.garfiec.librechat.core.model.UserFavorite
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.URLBuilder
import io.ktor.http.appendPathSegments
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

    /**
     * Marketplace-item favorites (built-in capabilities, plugin tools, MCP servers, skills),
     * added on the v0.8.8 line by #13952.
     *
     * A different collection from the list above, with per-item writes instead of a whole-list
     * overwrite — so the two never contend and neither needs the other's cache. Rows whose
     * `itemType` this build does not recognize are dropped rather than thrown on; see
     * [ToolFavoriteWire].
     */
    suspend fun getToolFavorites(): List<ToolFavorite> {
        val wire: List<ToolFavoriteWire> = client.get {
            url { path("api/user/settings/favorites/tools") }
        }.body()
        return wire.mapNotNull { it.toDomain() }
    }

    /** Idempotent server-side: re-pinning an already-pinned item is a 200 with `added:false`. */
    suspend fun addToolFavorite(itemType: ToolFavoriteItemType, itemId: String) {
        client.put {
            url { toolFavoritePath(itemType, itemId) }
        }
    }

    suspend fun removeToolFavorite(itemType: ToolFavoriteItemType, itemId: String) {
        client.delete {
            url { toolFavoritePath(itemType, itemId) }
        }
    }

    /**
     * `itemId` is an MCP server name or a plugin key straight out of the catalog, so it can carry
     * spaces and `/`. It is appended as a raw segment with `encodeSlash = true` so the builder
     * escapes it EXACTLY ONCE — `path()` would percent-encode it a second time (`%20` -> `%2520`),
     * and Express decodes route params only once, so the server would store and look up the
     * mangled id instead of the real one. An unescaped `/` would instead address a different
     * favorite entirely. Never pre-encode the id before handing it to the builder.
     */
    private fun URLBuilder.toolFavoritePath(itemType: ToolFavoriteItemType, itemId: String) {
        path("api/user/settings/favorites/tools")
        appendPathSegments(itemType.wireName, itemId, encodeSlash = true)
    }
}
