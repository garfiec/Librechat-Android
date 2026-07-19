package com.garfiec.librechat.feature.conversations.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.garfiec.librechat.core.common.extensions.RelativeTimeReference
import com.garfiec.librechat.core.common.extensions.dayBoundaryReferences
import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.data.repository.ConfigRepository
import com.garfiec.librechat.core.data.repository.ConversationRepository
import com.garfiec.librechat.core.data.repository.RoleRepository
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
import com.garfiec.librechat.feature.conversations.export.ExportFormat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Sort field for the project chat list (v0.8.7), matching the convos endpoint. */
enum class ProjectChatsSort(val sortBy: String) {
    UPDATED("updatedAt"),
    CREATED("createdAt"),
}

@Immutable
data class ProjectChatsUiState(
    val groupedConversations: List<Pair<String, List<ConversationDisplayData>>> = emptyList(),
    val conversationCount: Int = 0,
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val nextCursor: String? = null,
    val hasMore: Boolean = true,
    val sort: ProjectChatsSort = ProjectChatsSort.UPDATED,
    val tags: List<ConversationTag> = emptyList(),
    val bookmarksEnabled: Boolean = true,
)

/**
 * Project-filtered conversation list (v0.8.7 "Show all"). Network-direct: loads pages via
 * [ConversationRepository.getConversationsForProject] and holds them itself, since Room can't
 * filter by project. Mirrors [ConversationListViewModel]'s action surface so the screen can
 * reuse the same row + action-sheet machinery.
 */
class ProjectChatsViewModel(
    private val projectId: String,
    private val conversationRepository: ConversationRepository,
    private val tagRepository: TagRepository,
    private val shareRepository: ShareRepository,
    private val conversationExporter: ConversationExporter,
    private val roleRepository: RoleRepository,
    private val configRepository: ConfigRepository,
    // Defaulted rather than Koin-provided: this one is built by an explicit `viewModel { }` block
    // (projectId arrives via parametersOf), so a default costs no DI wiring. Tests override it —
    // the production flow never completes, which would hang `advanceUntilIdle()`.
    private val dayBoundaries: Flow<RelativeTimeReference> = dayBoundaryReferences(),
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProjectChatsUiState())
    val uiState: StateFlow<ProjectChatsUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<ConversationListEvent>()
    val events: SharedFlow<ConversationListEvent> = _events.asSharedFlow()

    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())

    private val actions = ConversationListActionsDelegate(
        scope = viewModelScope,
        events = _events,
        conversationRepository = conversationRepository,
        tagRepository = tagRepository,
        shareRepository = shareRepository,
        conversationExporter = conversationExporter,
        // Network-direct list: re-fetch after a mutation so the change is reflected.
        onMutated = { refresh() },
    )

    init {
        load()
        observeGrouped()
        observeTags()
        observePermissions()
    }

    private fun observeGrouped() {
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
                _uiState.value = _uiState.value.copy(groupedConversations = grouped, conversationCount = count)
            }
        }
    }

    private fun observeTags() {
        viewModelScope.launch {
            tagRepository.observeTags().collect { tags ->
                _uiState.value = _uiState.value.copy(tags = tags.filter { it.count > 0 && it.tag != SAVED_TAG })
            }
        }
    }

    private fun observePermissions() {
        viewModelScope.launch {
            roleRepository.userPermissions.collect { role ->
                _uiState.value = _uiState.value.copy(
                    bookmarksEnabled = role.hasAccessOrPermissive(PermissionType.BOOKMARKS, Permission.USE),
                )
            }
        }
    }

    fun getConversation(conversationId: String): Conversation? =
        _conversations.value.firstOrNull { it.conversationId == conversationId }

    fun load() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            fetchPage(cursor = null, replace = true)
            _uiState.value = _uiState.value.copy(isLoading = false)
        }
    }

    fun loadMore() {
        val cursor = _uiState.value.nextCursor ?: return
        // Guard against BOTH flags: a refresh (replace=true) running concurrently with this append
        // would resolve a stale cursor against a just-replaced list and could duplicate ids — which
        // crashes the LazyColumn's keyed items().
        if (_uiState.value.isLoading || _uiState.value.isRefreshing) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            fetchPage(cursor = cursor, replace = false)
            _uiState.value = _uiState.value.copy(isLoading = false)
        }
    }

    fun refresh() {
        if (_uiState.value.isRefreshing) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isRefreshing = true)
            fetchPage(cursor = null, replace = true)
            _uiState.value = _uiState.value.copy(isRefreshing = false)
        }
    }

    fun setSort(sort: ProjectChatsSort) {
        if (sort == _uiState.value.sort) return
        _uiState.value = _uiState.value.copy(sort = sort)
        load()
    }

    private suspend fun fetchPage(cursor: String?, replace: Boolean) {
        when (
            val result = conversationRepository.getConversationsForProject(
                projectId = projectId,
                cursor = cursor,
                sortBy = _uiState.value.sort.sortBy,
                sortDirection = "desc",
            )
        ) {
            is Result.Success -> {
                // distinctBy on append: defense-in-depth against a page boundary that reorders under
                // the `updatedAt` sort handing back an id already held — duplicate keys crash the list.
                _conversations.value =
                    if (replace) {
                        result.data.conversations
                    } else {
                        (_conversations.value + result.data.conversations).distinctBy { it.conversationId }
                    }
                _uiState.value = _uiState.value.copy(
                    nextCursor = result.data.nextCursor,
                    hasMore = result.data.nextCursor != null,
                )
            }
            is Result.Error -> _uiState.value = _uiState.value.copy(error = result.message)
            is Result.Loading -> Unit
        }
    }

    fun toggleFavorite(conversation: Conversation) = actions.toggleFavorite(conversation)

    fun renameConversation(id: String, newTitle: String) = actions.renameConversation(id, newTitle)

    fun archiveConversation(id: String) = actions.archiveConversation(id)

    fun deleteConversation(id: String) = actions.deleteConversation(id)

    fun shareConversation(conversationId: String) = actions.shareConversation(conversationId)

    fun duplicateConversation(conversationId: String, title: String?) =
        actions.duplicateConversation(conversationId, title)

    fun updateConversationTags(conversation: Conversation, userTags: List<String>) =
        actions.updateConversationTags(conversation, userTags)

    fun exportConversation(conversationId: String, title: String?, format: ExportFormat) =
        actions.exportConversation(conversationId, title, format)

    fun dismissError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}
