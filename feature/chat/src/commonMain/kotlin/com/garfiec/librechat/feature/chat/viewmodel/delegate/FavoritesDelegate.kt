package com.garfiec.librechat.feature.chat.viewmodel.delegate

import co.touchlab.kermit.Logger
import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.data.repository.FavoritesRepository
import com.garfiec.librechat.core.model.FavoritesLimits
import com.garfiec.librechat.core.model.UserFavorite
import com.garfiec.librechat.feature.chat.viewmodel.FavoritesHandle
import kotlinx.coroutines.launch

/**
 * Routes pin/unpin actions from chat-side pickers (model/agent selector) to
 * [FavoritesRepository] and republishes the canonical list into its [FavoritesHandle].
 *
 * The repository owns the cached list and serializes writes via a Mutex, so
 * this delegate is a thin shim: it observes the shared flow and forwards
 * toggles. The settings-side `FavoritesViewModel` reads the same flow.
 *
 * Spec pinning is intentionally not surfaced yet — mobile has no spec picker
 * today, but [UserFavorite.spec] is preserved in round-trips so third-party
 * clients' pinned specs survive a save from this client.
 */
class FavoritesDelegate(
    private val handle: FavoritesHandle,
    private val favoritesRepository: FavoritesRepository,
) {

    fun load() {
        observeFavorites()
        refresh()
    }

    /**
     * Re-fetches favorites from the server. Called at VM init and again whenever the
     * model sheet opens: the init fetch is single-shot and its failure is swallowed,
     * so a cold-start fetch that races auth/network warm-up would otherwise leave the
     * picker starless (no top Starred section, unfilled stars) until a pin is toggled.
     * The sheet-open refetch self-heals that and also picks up pins made elsewhere.
     */
    fun refresh() {
        handle.scope.launch {
            when (val result = favoritesRepository.refresh()) {
                is Result.Success -> Unit
                is Result.Error -> {
                    // Non-critical; leave favorites empty in UI.
                    Logger.d(result.exception) { "Failed to load favorites: ${result.message}" }
                }
                is Result.Loading -> Unit
            }
        }
    }

    private fun observeFavorites() {
        handle.scope.launch {
            favoritesRepository.favorites.collect { list -> publish(list) }
        }
    }

    /** Toggle an agent pin. Fire-and-forget — the repository does the optimistic update + rollback. */
    fun toggleAgent(agentId: String) {
        val current = favoritesRepository.favorites.value
        val currentlyPinned = current.any { it.agentId == agentId }
        val nextList = if (currentlyPinned) {
            current.filterNot { it.agentId == agentId }
        } else {
            if (atLimit(current)) {
                reportLimit()
                return
            }
            current + UserFavorite(agentId = agentId)
        }
        persist(nextList)
    }

    /** Toggle a model pin. A model favorite is identified by the (endpoint, model) pair. */
    fun toggleModel(endpoint: String, model: String) {
        val current = favoritesRepository.favorites.value
        val currentlyPinned = current.any { it.endpoint == endpoint && it.model == model }
        val nextList = if (currentlyPinned) {
            current.filterNot { it.endpoint == endpoint && it.model == model }
        } else {
            if (atLimit(current)) {
                reportLimit()
                return
            }
            current + UserFavorite(endpoint = endpoint, model = model)
        }
        persist(nextList)
    }

    private fun atLimit(current: List<UserFavorite>): Boolean = current.size >= FavoritesLimits.MAX_FAVORITES

    private fun reportLimit() {
        handle.setError("You can pin up to ${FavoritesLimits.MAX_FAVORITES} favorites.")
    }

    private fun persist(nextList: List<UserFavorite>) {
        handle.scope.launch {
            when (val result = favoritesRepository.setFavorites(nextList)) {
                is Result.Success -> Unit
                is Result.Error -> {
                    Logger.w(result.exception) { "Failed to save favorites: ${result.message}" }
                    handle.setError(result.message ?: "Could not update favorites.")
                }
                is Result.Loading -> Unit
            }
        }
    }

    private fun publish(list: List<UserFavorite>) {
        val agentIds = list.mapNotNullTo(mutableSetOf()) { it.agentId }
        val modelKeys = list.mapNotNullTo(mutableSetOf()) {
            val endpoint = it.endpoint
            val model = it.model
            if (endpoint != null && model != null) favoriteModelKey(endpoint, model) else null
        }
        handle.update {
            favorites = favorites.copy(
                favoriteAgentIds = agentIds,
                favoriteModelKeys = modelKeys,
            )
        }
    }

    companion object {
        private const val KEY_SEPARATOR = "::"

        /** Stable composite key for a (endpoint, model) favorite — used by pickers for membership checks and sorting. */
        fun favoriteModelKey(endpoint: String, model: String): String = "$endpoint$KEY_SEPARATOR$model"

        /** Inverse of [favoriteModelKey]: splits a key back into (endpoint, model), or null if malformed. */
        fun parseFavoriteModelKey(key: String): Pair<String, String>? {
            val sep = key.indexOf(KEY_SEPARATOR)
            if (sep < 0) return null
            return key.substring(0, sep) to key.substring(sep + KEY_SEPARATOR.length)
        }
    }
}
