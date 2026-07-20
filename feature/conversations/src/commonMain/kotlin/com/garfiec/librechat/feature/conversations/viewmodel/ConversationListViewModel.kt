package com.garfiec.librechat.feature.conversations.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.garfiec.librechat.core.common.extensions.RelativeTimeReference
import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.data.repository.ConfigRepository
import com.garfiec.librechat.core.data.repository.ConversationRepository
import com.garfiec.librechat.core.data.repository.RoleRepository
import com.garfiec.librechat.core.data.repository.SearchRepository
import com.garfiec.librechat.core.data.repository.ShareRepository
import com.garfiec.librechat.core.data.repository.TagRepository
import com.garfiec.librechat.core.model.Conversation
import com.garfiec.librechat.core.model.ConversationTag
import com.garfiec.librechat.core.model.SAVED_TAG
import com.garfiec.librechat.core.model.permissions.Permission
import com.garfiec.librechat.core.model.permissions.PermissionType
import com.garfiec.librechat.core.model.permissions.hasAccessOrPermissive
import com.garfiec.librechat.feature.conversations.components.ConversationDisplayData
import com.garfiec.librechat.feature.conversations.export.ConversationExporter
import com.garfiec.librechat.feature.conversations.export.ConversationImporter
import com.garfiec.librechat.feature.conversations.export.ExportFormat
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
data class ConversationListUiState(
    val groupedConversations: List<Pair<String, List<ConversationDisplayData>>> = emptyList(),
    val conversationCount: Int = 0,
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val nextCursor: String? = null,
    val hasMore: Boolean = true,
    val tags: List<ConversationTag> = emptyList(),
    val selectedTags: Set<String> = emptySet(),
    val searchQuery: String = "",
    val isSearching: Boolean = false,
    // Role-permission gate — default permissive until role loads.
    val bookmarksEnabled: Boolean = true,
)

sealed interface ConversationListEvent {
    data class ShareLinkCopied(val url: String) : ConversationListEvent
    data class NavigateToConversation(val conversationId: String) : ConversationListEvent

    /** The currently-open conversation was deleted: move off it to a fresh new chat. */
    data object NavigateToNewChat : ConversationListEvent
    data class ShowError(val message: String) : ConversationListEvent
    data class ExportReady(val content: String, val format: ExportFormat, val title: String) : ConversationListEvent
    data class ImportSuccess(val title: String) : ConversationListEvent
}

class ConversationListViewModel(
    private val conversationRepository: ConversationRepository,
    private val tagRepository: TagRepository,
    private val searchRepository: SearchRepository,
    private val shareRepository: ShareRepository,
    private val conversationExporter: ConversationExporter,
    private val conversationImporter: ConversationImporter,
    private val roleRepository: RoleRepository,
    private val configRepository: ConfigRepository,
    // Koin-provided rather than defaulted, so `viewModelOf` (and the static `verify()` coverage it
    // buys) still applies. Tests inject a finite flow: the production one never completes, and a
    // repeating delay makes `advanceUntilIdle()` advance virtual time forever.
    private val dayBoundaries: Flow<RelativeTimeReference>,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ConversationListUiState())
    val uiState: StateFlow<ConversationListUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<ConversationListEvent>()
    val events: SharedFlow<ConversationListEvent> = _events.asSharedFlow()

    /** Raw conversations kept for internal lookups (action sheets, etc.). */
    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
    private var searchJob: Job? = null

    // The last Room emission, so an active search can tell a genuine delete/archive (present ->
    // absent) from a row merely outside Room's fetched window. Kept as the raw list (a reference,
    // not a Set) so the non-search path allocates nothing; the id sets are built only while searching.
    private var lastRoomData: List<Conversation> = emptyList()

    private val actions = ConversationListActionsDelegate(
        scope = viewModelScope,
        events = _events,
        conversationRepository = conversationRepository,
        tagRepository = tagRepository,
        shareRepository = shareRepository,
        conversationExporter = conversationExporter,
        // Room-backed list: observeConversations re-emits on mutation, so no manual refresh.
        onMutated = {},
    )

    init {
        loadConversations()
        observeConversations()
        observeTags()
        observePermissions()
        observeGroupedConversations()
    }

    private fun observePermissions() {
        viewModelScope.launch {
            roleRepository.userPermissions.collect { role ->
                _uiState.value = _uiState.value.copy(
                    bookmarksEnabled = role.hasAccessOrPermissive(
                        PermissionType.BOOKMARKS,
                        Permission.USE,
                    ),
                )
            }
        }
    }

    private fun observeConversations() {
        viewModelScope.launch {
            conversationRepository.observeConversations().collect { result ->
                when (result) {
                    is Result.Success -> {
                        if (_uiState.value.searchQuery.isEmpty()) {
                            _conversations.value = result.data
                            _uiState.value = _uiState.value.copy(isLoading = false)
                        } else if (result.data.isNotEmpty()) {
                            // Active search: results are server-sourced and not written to Room, so
                            // don't replace them wholesale (that wipes server-only hits) nor union
                            // Room in (that pollutes results). Room still re-emits on local mutations,
                            // so reconcile the visible results against it: drop rows that just left
                            // Room (delete/archive from the drawer) and pull in locally-edited rows
                            // (rename/tags/pin/favorite stamp updatedAt = now, so Room is >= as fresh),
                            // but never let a stale cache row clobber a server-fresher hit that hasn't
                            // synced to Room yet. An empty emission is an account-scoped reset (the
                            // active-account gate), not a per-row delete, so it is ignored here — the
                            // screen reloads for the new account rather than blanking the old results.
                            val roomIds = result.data.mapNotNullTo(HashSet()) { it.conversationId }
                            val lastIds = lastRoomData.mapNotNullTo(HashSet()) { it.conversationId }
                            val removed = lastIds - roomIds
                            val byId = result.data.associateBy { it.conversationId }
                            _conversations.update { current ->
                                current.mapNotNull { c ->
                                    val id = c.conversationId ?: return@mapNotNull c
                                    if (id in removed) null else byId[id]?.takeIf { it.isNotOlderThan(c) } ?: c
                                }
                            }
                        }
                        lastRoomData = result.data
                    }
                    is Result.Error -> {
                        _uiState.value = _uiState.value.copy(
                            error = result.message,
                            isLoading = false,
                        )
                    }
                    is Result.Loading -> { /* no-op */ }
                }
            }
        }
    }

    /**
     * True when `this` (a Room row) is at least as fresh as [other] (a server search hit), by
     * `updatedAt`. Local edits stamp `updatedAt = now`, so they compare newer and are reflected;
     * a cross-device change not yet synced leaves Room strictly older, so it is not swapped in.
     * Falls back to `false` (keep the server hit) when either timestamp is missing.
     */
    private fun Conversation.isNotOlderThan(other: Conversation): Boolean {
        val mine = updatedAt ?: return false
        val theirs = other.updatedAt ?: return false
        return mine >= theirs
    }

    private fun observeTags() {
        viewModelScope.launch {
            tagRepository.observeTags().collect { tags ->
                _uiState.value = _uiState.value.copy(
                    tags = tags.filter { it.count > 0 && it.tag != SAVED_TAG },
                )
            }
        }
    }

    private fun observeGroupedConversations() {
        viewModelScope.launch {
            // dayBoundaryReferences() re-groups at local midnight: date sections are clock-dependent
            // and membership (not just the label) changes when the date rolls over.
            combine(
                _conversations,
                configRepository.endpointConfigs,
                dayBoundaries,
            ) { convos, configs, reference ->
                groupConversationsByDate(convos, configs, reference) to convos.size
            }.collect { (grouped, count) ->
                _uiState.value = _uiState.value.copy(
                    groupedConversations = grouped,
                    conversationCount = count,
                )
            }
        }
    }

    fun getConversation(conversationId: String): Conversation? =
        _conversations.value.firstOrNull { it.conversationId == conversationId }

    fun toggleFavorite(conversation: Conversation) = actions.toggleFavorite(conversation)

    fun loadConversations() {
        val activeTags = _uiState.value.selectedTags.toList().ifEmpty { null }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            when (val result = conversationRepository.loadNextPage(null, tags = activeTags)) {
                is Result.Success -> {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        nextCursor = result.data,
                        hasMore = result.data != null,
                    )
                }
                is Result.Error -> {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = result.message,
                    )
                }
                is Result.Loading -> { /* no-op */ }
            }
        }
    }

    fun loadMore() {
        val cursor = _uiState.value.nextCursor ?: return
        if (_uiState.value.isLoading) return

        val activeTags = _uiState.value.selectedTags.toList().ifEmpty { null }
        viewModelScope.launch {
            when (val result = conversationRepository.loadNextPage(cursor, tags = activeTags)) {
                is Result.Success -> {
                    _uiState.value = _uiState.value.copy(
                        nextCursor = result.data,
                        hasMore = result.data != null,
                    )
                }
                is Result.Error -> {
                    _uiState.value = _uiState.value.copy(error = result.message)
                }
                is Result.Loading -> { /* no-op */ }
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isRefreshing = true)
            val activeTags = _uiState.value.selectedTags.toList().ifEmpty { null }
            coroutineScope {
                launch {
                    when (val result = conversationRepository.loadNextPage(null, tags = activeTags)) {
                        is Result.Success -> {
                            _uiState.value = _uiState.value.copy(
                                nextCursor = result.data,
                                hasMore = result.data != null,
                            )
                        }
                        is Result.Error -> {
                            _uiState.value = _uiState.value.copy(error = result.message)
                        }
                        is Result.Loading -> { /* no-op */ }
                    }
                }
                launch { tagRepository.refreshTags() }
                launch { conversationRepository.syncFavoritesFromServer() }
            }
            _uiState.value = _uiState.value.copy(isRefreshing = false)
        }
    }

    fun toggleTag(tag: String) {
        val current = _uiState.value.selectedTags
        val updated = if (tag in current) current - tag else current + tag
        _uiState.value = _uiState.value.copy(selectedTags = updated)
        // Reload conversations with new tag filter
        loadConversations()
    }

    fun clearTagFilter() {
        _uiState.value = _uiState.value.copy(selectedTags = emptySet())
        loadConversations()
    }

    fun onSearchQueryChanged(query: String) {
        _uiState.value = _uiState.value.copy(searchQuery = query)
        searchJob?.cancel()

        if (query.isBlank()) {
            _uiState.value = _uiState.value.copy(isSearching = false)
            // Reload normal conversation list
            loadConversations()
            return
        }

        searchJob = viewModelScope.launch {
            delay(500) // 500ms debounce
            _uiState.value = _uiState.value.copy(isSearching = true)
            when (val result = searchRepository.search(query)) {
                is Result.Success -> {
                    _conversations.value = result.data
                    _uiState.value = _uiState.value.copy(
                        isSearching = false,
                        hasMore = false,
                    )
                }
                is Result.Error -> {
                    _uiState.value = _uiState.value.copy(
                        isSearching = false,
                        error = result.message,
                    )
                }
                is Result.Loading -> { /* no-op */ }
            }
        }
    }

    fun updateConversationTags(conversation: Conversation, userTags: List<String>) =
        actions.updateConversationTags(conversation, userTags)

    fun renameConversation(id: String, newTitle: String) = actions.renameConversation(id, newTitle)

    fun archiveConversation(id: String) = actions.archiveConversation(id)

    fun deleteConversation(id: String) = actions.deleteConversation(id)

    fun dismissError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    fun shareConversation(conversationId: String) = actions.shareConversation(conversationId)

    fun forkConversation(conversationId: String, messageId: String) {
        viewModelScope.launch {
            when (val result = conversationRepository.forkConversation(conversationId, messageId)) {
                is Result.Success -> {
                    result.data.conversationId?.let { newId ->
                        _events.emit(ConversationListEvent.NavigateToConversation(newId))
                    }
                }
                is Result.Error -> {
                    _events.emit(
                        ConversationListEvent.ShowError(result.message ?: "Failed to fork conversation"),
                    )
                }
                is Result.Loading -> { /* no-op */ }
            }
        }
    }

    fun duplicateConversation(conversationId: String, title: String?) =
        actions.duplicateConversation(conversationId, title)

    fun exportConversation(conversationId: String, title: String?, format: ExportFormat) =
        actions.exportConversation(conversationId, title, format)

    fun importConversation(jsonContent: String) {
        viewModelScope.launch {
            // First validate the JSON format
            when (val parseResult = conversationImporter.parseJson(jsonContent)) {
                is Result.Success -> {
                    val export = parseResult.data
                    // Now upload to server
                    when (val importResult = conversationRepository.importConversation(jsonContent)) {
                        is Result.Success -> {
                            _events.emit(
                                ConversationListEvent.ImportSuccess(
                                    title = export.conversation.title ?: "Imported conversation",
                                ),
                            )
                            refresh()
                        }
                        is Result.Error -> {
                            _events.emit(
                                ConversationListEvent.ShowError(
                                    importResult.message ?: "Failed to upload conversation to server",
                                ),
                            )
                        }
                        is Result.Loading -> { /* no-op */ }
                    }
                }
                is Result.Error -> {
                    _events.emit(
                        ConversationListEvent.ShowError(parseResult.message ?: "Failed to parse conversation file"),
                    )
                }
                is Result.Loading -> { /* no-op */ }
            }
        }
    }
}
