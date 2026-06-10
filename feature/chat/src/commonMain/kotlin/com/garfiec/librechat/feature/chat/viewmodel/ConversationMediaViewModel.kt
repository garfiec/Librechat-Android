package com.garfiec.librechat.feature.chat.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.data.datastore.ServerDataStore
import com.garfiec.librechat.core.data.repository.FileRepository
import com.garfiec.librechat.core.data.repository.MessageRepository
import com.garfiec.librechat.core.data.repository.UserRepository
import com.garfiec.librechat.core.model.Message
import com.garfiec.librechat.core.ui.media.MediaItem
import com.garfiec.librechat.core.ui.media.MediaPreviewState
import com.garfiec.librechat.feature.chat.components.artifact.Artifact
import com.garfiec.librechat.feature.chat.util.ConversationFile
import com.garfiec.librechat.feature.chat.util.ConversationLink
import com.garfiec.librechat.feature.chat.util.buildActiveMessagePath
import com.garfiec.librechat.feature.chat.util.extractConversationArtifacts
import com.garfiec.librechat.feature.chat.util.extractConversationFiles
import com.garfiec.librechat.feature.chat.util.extractConversationLinks
import com.garfiec.librechat.feature.chat.util.extractConversationMedia
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Whether the gallery shows media from the whole conversation tree or only the active branch. */
enum class MediaScope { ENTIRE, ACTIVE_BRANCH }

data class ConversationMediaUiState(
    val isLoading: Boolean = true,
    val scope: MediaScope = MediaScope.ACTIVE_BRANCH,
    val media: List<MediaItem> = emptyList(),
    val files: List<ConversationFile> = emptyList(),
    val links: List<ConversationLink> = emptyList(),
    /** Each entry is one artifact's version history (sorted ascending); tile shows the latest. */
    val artifacts: List<List<Artifact>> = emptyList(),
    val mediaPreview: MediaPreviewState? = null,
    val error: String? = null,
)

/**
 * Backs the "Show all media" gallery for a single conversation. Loads the full (unpaginated)
 * message list once, then derives the Media / Files / Links / Artifacts tabs for the current
 * [MediaScope]. Pure read — never writes Room or mutates branch state.
 */
class ConversationMediaViewModel(
    private val conversationId: String,
    private val messageRepository: MessageRepository,
    private val fileRepository: FileRepository,
    private val userRepository: UserRepository,
    private val serverDataStore: ServerDataStore,
    private val defaultDispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ConversationMediaUiState())
    val uiState: StateFlow<ConversationMediaUiState> = _uiState.asStateFlow()

    private var allMessages: List<Message> = emptyList()
    private var baseUrl: String = ""

    // Cached so tapping several files doesn't re-fetch the user on each download.
    private var cachedUserId: String? = null

    init {
        load()
    }

    private fun load() {
        viewModelScope.launch {
            baseUrl = serverDataStore.awaitBaseUrl()
            when (val result = messageRepository.getMessages(conversationId)) {
                is Result.Success -> {
                    allMessages = result.data
                    recompute()
                }
                is Result.Error -> {
                    Logger.e(result.exception) { "Failed to load conversation media: ${result.message}" }
                    _uiState.update { it.copy(isLoading = false, error = result.message) }
                }
                is Result.Loading -> Unit
            }
        }
    }

    fun setScope(scope: MediaScope) {
        if (scope == _uiState.value.scope) return
        _uiState.update { it.copy(scope = scope) }
        viewModelScope.launch { recompute() }
    }

    // Extraction runs the active-branch tree-build plus four regex/scan passes over the full message
    // text, so it stays off the main thread to keep load and scope toggles from hitching the UI.
    private suspend fun recompute() {
        val scope = _uiState.value.scope
        val derived = withContext(defaultDispatcher) {
            val messages = when (scope) {
                MediaScope.ENTIRE -> allMessages
                MediaScope.ACTIVE_BRANCH -> buildActiveMessagePath(allMessages).map { it.message }
            }
            DerivedTabs(
                media = extractConversationMedia(messages, baseUrl),
                files = extractConversationFiles(messages),
                links = extractConversationLinks(messages),
                artifacts = extractConversationArtifacts(messages),
            )
        }
        _uiState.update {
            it.copy(
                isLoading = false,
                media = derived.media,
                files = derived.files,
                links = derived.links,
                artifacts = derived.artifacts,
            )
        }
    }

    private data class DerivedTabs(
        val media: List<MediaItem>,
        val files: List<ConversationFile>,
        val links: List<ConversationLink>,
        val artifacts: List<List<Artifact>>,
    )

    /**
     * Opens the full-screen pager at [url], swiping over the whole Media tab (the current scope's
     * image set) — same viewer as the in-chat one. Falls back to index 0 if [url] isn't found.
     */
    fun openMedia(url: String) {
        if (url.isBlank()) return
        val items = _uiState.value.media
        if (items.isEmpty()) return
        val index = items.indexOfFirst { it.url == url }.takeIf { it >= 0 } ?: 0
        _uiState.update { it.copy(mediaPreview = MediaPreviewState(items = items, initialIndex = index)) }
    }

    fun closeMedia() {
        _uiState.update { it.copy(mediaPreview = null) }
    }

    /** Downloads a file's bytes (authenticated) for the Files-tab share action; null on failure. */
    suspend fun downloadFileBytes(fileId: String): ByteArray? {
        val userId = cachedUserId ?: resolveUserId() ?: return null
        return when (val result = fileRepository.downloadFile(userId, fileId)) {
            is Result.Success -> result.data
            is Result.Error -> {
                Logger.e(result.exception) { "Failed to download file $fileId: ${result.message}" }
                null
            }
            is Result.Loading -> null
        }
    }

    private suspend fun resolveUserId(): String? {
        val id = (userRepository.getUser() as? Result.Success)?.data?.id
        cachedUserId = id
        return id
    }
}
