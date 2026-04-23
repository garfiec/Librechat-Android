package com.garfiec.librechat.feature.chat.viewmodel.delegate

import co.touchlab.kermit.Logger
import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.data.repository.FavoritesRepository
import com.garfiec.librechat.core.model.FavoritesLimits
import com.garfiec.librechat.core.model.UserFavorite
import com.garfiec.librechat.feature.chat.viewmodel.ChatStateHandle
import kotlinx.coroutines.launch

/**
 * Owns the user's pinned-favorites list (`GET/POST /api/user/settings/favorites`, v0.8.5+).
 *
 * Mirror the web UX: pin icons in model/agent pickers. Pinned items sort to the
 * top of their groups. Spec pinning is intentionally not surfaced yet — mobile
 * has no spec picker today, but [UserFavorite.spec] is preserved in round-trips
 * so third-party clients' pinned specs survive a save from this client.
 *
 * The server replaces the entire list on each POST (upsert-by-overwrite), so we
 * snapshot [cached] locally and emit the full mutated list on each toggle.
 */
class FavoritesDelegate(
    private val stateHandle: ChatStateHandle,
    private val favoritesRepository: FavoritesRepository,
) {

    private var cached: List<UserFavorite> = emptyList()

    fun load() {
        stateHandle.scope.launch {
            when (val result = favoritesRepository.getFavorites()) {
                is Result.Success -> {
                    cached = result.data
                    publish()
                }
                is Result.Error -> {
                    // Non-critical; leave favorites empty in UI.
                    Logger.d(result.exception) { "Failed to load favorites: ${result.message}" }
                }
                is Result.Loading -> Unit
            }
        }
    }

    /** Toggle an agent pin. Fire-and-forget — updates optimistically then reconciles from the server response. */
    fun toggleAgent(agentId: String) {
        val currentlyPinned = cached.any { it.agentId == agentId }
        val nextList = if (currentlyPinned) {
            cached.filterNot { it.agentId == agentId }
        } else {
            if (atLimit()) {
                reportLimit()
                return
            }
            cached + UserFavorite(agentId = agentId)
        }
        persist(nextList)
    }

    /** Toggle a model pin. A model favorite is identified by the (endpoint, model) pair. */
    fun toggleModel(endpoint: String, model: String) {
        val currentlyPinned = cached.any { it.endpoint == endpoint && it.model == model }
        val nextList = if (currentlyPinned) {
            cached.filterNot { it.endpoint == endpoint && it.model == model }
        } else {
            if (atLimit()) {
                reportLimit()
                return
            }
            cached + UserFavorite(endpoint = endpoint, model = model)
        }
        persist(nextList)
    }

    private fun atLimit(): Boolean = cached.size >= FavoritesLimits.MAX_FAVORITES

    private fun reportLimit() {
        stateHandle.update {
            copy(error = "You can pin up to ${FavoritesLimits.MAX_FAVORITES} favorites.")
        }
    }

    private fun persist(nextList: List<UserFavorite>) {
        // Optimistic: update cached immediately so the star toggles without the network round-trip.
        cached = nextList
        publish()
        stateHandle.scope.launch {
            when (val result = favoritesRepository.setFavorites(nextList)) {
                is Result.Success -> {
                    cached = result.data
                    publish()
                }
                is Result.Error -> {
                    Logger.w(result.exception) { "Failed to save favorites: ${result.message}" }
                    // Roll back to whatever the server last accepted.
                    when (val refetch = favoritesRepository.getFavorites()) {
                        is Result.Success -> {
                            cached = refetch.data
                            publish()
                        }
                        else -> Unit
                    }
                    stateHandle.update { copy(error = result.message ?: "Could not update favorites.") }
                }
                is Result.Loading -> Unit
            }
        }
    }

    private fun publish() {
        val agentIds = cached.mapNotNullTo(mutableSetOf()) { it.agentId }
        val modelKeys = cached.mapNotNullTo(mutableSetOf()) {
            val endpoint = it.endpoint
            val model = it.model
            if (endpoint != null && model != null) favoriteModelKey(endpoint, model) else null
        }
        stateHandle.update {
            copy(
                favoriteAgentIds = agentIds,
                favoriteModelKeys = modelKeys,
            )
        }
    }

    companion object {
        /** Stable composite key for a (endpoint, model) favorite — used by pickers for membership checks and sorting. */
        fun favoriteModelKey(endpoint: String, model: String): String = "$endpoint::$model"
    }
}
