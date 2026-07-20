package com.garfiec.librechat.feature.conversations.drawer

import co.touchlab.kermit.Logger
import com.garfiec.librechat.core.common.extensions.RelativeTimeReference
import com.garfiec.librechat.core.common.extensions.dayBoundaryReferences
import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.data.repository.ConversationRepository
import com.garfiec.librechat.core.model.Conversation
import com.garfiec.librechat.feature.conversations.viewmodel.groupedByDateBucket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

class ConversationListStateHolder(
    private val conversationRepository: ConversationRepository,
    private val scope: CoroutineScope,
    // Injectable so tests can supply a finite flow: the production one never completes, and a
    // repeating delay makes `advanceUntilIdle()` advance virtual time forever.
    private val dayBoundaries: Flow<RelativeTimeReference> = dayBoundaryReferences(),
) {

    companion object {
        private const val SEARCH_DEBOUNCE_MS = 300L
    }

    private val _recentConversations = MutableStateFlow<List<Conversation>>(emptyList())
    val recentConversations: StateFlow<List<Conversation>> = _recentConversations.asStateFlow()

    private val _groupedConversations = MutableStateFlow<List<Pair<String, List<Conversation>>>>(emptyList())
    val groupedConversations: StateFlow<List<Pair<String, List<Conversation>>>> = _groupedConversations.asStateFlow()

    private val _activeConversationId = MutableStateFlow<String?>(null)
    val activeConversationId: StateFlow<String?> = _activeConversationId.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private var nextCursor: String? = null

    private val _hasMore = MutableStateFlow(true)
    val hasMore: StateFlow<Boolean> = _hasMore.asStateFlow()

    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private var searchObserverJob: Job? = null

    init {
        observeConversations()
        observeSearchQuery()
        observeDayBoundary()
        loadInitialConversations()
    }

    private fun observeConversations() {
        scope.launch {
            try {
                conversationRepository.observeConversations().collect { result ->
                    if (result is Result.Success) {
                        _recentConversations.value = result.data
                        // Regroup on every Room emission — including during an active search. Search
                        // is a client-side filter over live Room data, so a delete/rename/pin/favorite
                        // (which re-emits Room) must reflect in the visible results immediately, not
                        // wait for the next query keystroke (Punted Bug #25, drawer side).
                        regroup()
                    }
                }
            } catch (e: Exception) {
                Logger.w(e) { "Failed to observe conversations" }
            }
        }
    }

    /**
     * Re-bucket at each local midnight. Date groups are clock-dependent, and a conversation crossing
     * midnight changes *section*, not just label — so unlike a row's "5m ago" this cannot be fixed
     * by reformatting at render time. Without this, a drawer left open overnight keeps yesterday's
     * sections until some unrelated Room emission happens to regroup.
     */
    private fun observeDayBoundary() {
        scope.launch {
            dayBoundaries.collect { regroup() }
        }
    }

    /** Rebuilds [_groupedConversations] from the current cache, honouring any active search. */
    private fun regroup() {
        val conversations = _recentConversations.value
        val query = _searchQuery.value
        _groupedConversations.value = if (query.isBlank()) {
            conversations.withoutPinned().groupedByDateBucket()
        } else {
            filterByQuery(conversations, query).groupedByDateBucket()
        }
    }

    private fun loadInitialConversations() {
        scope.launch {
            loadPage(cursor = null)
        }
    }

    private suspend fun loadPage(cursor: String?): Boolean {
        return try {
            val result = conversationRepository.loadNextPage(cursor = cursor)
            if (result is Result.Success) {
                nextCursor = result.data
                _hasMore.value = result.data != null
                true
            } else {
                Logger.w { "Failed to load conversations page" }
                false
            }
        } catch (e: Exception) {
            Logger.w(e) { "Failed to load conversations page" }
            false
        }
    }

    fun loadMoreConversations() {
        if (_isLoadingMore.value || !_hasMore.value) return
        _isLoadingMore.value = true
        scope.launch {
            loadPage(cursor = nextCursor)
            _isLoadingMore.value = false
        }
    }

    fun refreshConversations() {
        _isRefreshing.value = true
        scope.launch {
            nextCursor = null
            _hasMore.value = true
            coroutineScope {
                launch { loadPage(cursor = null) }
                launch { conversationRepository.syncFavoritesFromServer() }
            }
            _isRefreshing.value = false
        }
    }

    fun setActiveConversation(conversationId: String?) {
        _activeConversationId.value = conversationId
    }

    fun onSearchQueryChanged(query: String) {
        _searchQuery.value = query
        if (query.isBlank()) {
            regroup()
        }
    }

    @OptIn(FlowPreview::class)
    private fun observeSearchQuery() {
        searchObserverJob?.cancel()
        searchObserverJob = scope.launch {
            _searchQuery
                .filter { it.isNotBlank() }
                .debounce(SEARCH_DEBOUNCE_MS)
                .collectLatest { query ->
                    _groupedConversations.value =
                        filterByQuery(_recentConversations.value, query).groupedByDateBucket()
                }
        }
    }

    private fun filterByQuery(conversations: List<Conversation>, query: String): List<Conversation> =
        conversations.filter { it.title?.contains(query, ignoreCase = true) == true }

    fun reset() {
        searchObserverJob?.cancel()
        searchObserverJob = null
        _recentConversations.value = emptyList()
        _groupedConversations.value = emptyList()
        _activeConversationId.value = null
        _searchQuery.value = ""
        nextCursor = null
        _hasMore.value = true
        _isLoadingMore.value = false
        _isRefreshing.value = false
        observeSearchQuery()
    }

    /**
     * Drops pinned conversations so they aren't double-listed: the dedicated Pinned drawer section
     * is their canonical home when not searching. Search keeps them (that section is hidden then),
     * so this is only applied on the non-search grouping paths.
     */
    private fun List<Conversation>.withoutPinned(): List<Conversation> = filterNot { it.pinned == true }
}
