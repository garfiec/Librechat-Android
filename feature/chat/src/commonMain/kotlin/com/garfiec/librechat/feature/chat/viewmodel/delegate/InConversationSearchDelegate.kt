package com.garfiec.librechat.feature.chat.viewmodel.delegate

import com.garfiec.librechat.feature.chat.components.countMessageOccurrences
import com.garfiec.librechat.feature.chat.util.collapseParallelToPrimary
import com.garfiec.librechat.feature.chat.viewmodel.SearchFocusRequest
import com.garfiec.librechat.feature.chat.viewmodel.SearchHandle
import com.garfiec.librechat.feature.chat.viewmodel.SearchMatch

class InConversationSearchDelegate(
    private val handle: SearchHandle,
) {

    /** Monotonic id so consecutive requests are never equal (see [SearchFocusRequest]). */
    private var nextFocusRequestId = 0L

    fun openSearch() {
        handle.update {
            search = search.copy(
                isSearchOpen = true,
                searchQuery = "",
                searchMatchIndices = emptyList(),
                currentSearchMatchIndex = 0,
                searchFocusRequest = null,
            )
        }
    }

    fun closeSearch() {
        handle.update {
            search = search.copy(
                isSearchOpen = false,
                searchQuery = "",
                searchMatchIndices = emptyList(),
                currentSearchMatchIndex = 0,
                searchFocusRequest = null,
            )
        }
    }

    fun onSearchQueryChanged(query: String) {
        val state = handle.state
        if (query.isBlank()) {
            handle.update {
                search = search.copy(
                    searchQuery = query,
                    searchMatchIndices = emptyList(),
                    currentSearchMatchIndex = 0,
                    searchFocusRequest = null,
                )
            }
            return
        }

        // Occurrences are numbered by the shared render-order walk so the renderer
        // resolves the exact same occurrence the delegate counted (SearchMatchEnumeration).
        // Enumerate over the SAME parts the list renders: a parallel (Compare-Models) turn
        // renders only its primary-agent parts — collapseParallelToPrimary in the single list
        // and partsForPane(secondary=false) in the primary comparison pane — so counting the raw
        // message would over-count the secondary agent's occurrences and desync the focus index
        // (dead next/prev stops, wrong-occurrence highlight). The collapse maps 1:1, so
        // messageIndex still aligns with displayMessages.
        val renderedMessages = collapseParallelToPrimary(state.displayMessages)
        val allMatches = mutableListOf<SearchMatch>()
        renderedMessages.forEachIndexed { index, node ->
            repeat(countMessageOccurrences(node.message, query)) { occurrence ->
                allMatches.add(SearchMatch(messageIndex = index, occurrenceInMessage = occurrence))
            }
        }

        handle.update {
            search = search.copy(
                searchQuery = query,
                searchMatchIndices = allMatches,
                currentSearchMatchIndex = 0,
                searchFocusRequest = allMatches.firstOrNull()?.let { focusRequestFor(it) },
            )
        }
    }

    fun nextSearchMatch() {
        val state = handle.state
        if (state.searchMatchIndices.isEmpty()) return
        val nextIndex = (state.currentSearchMatchIndex + 1) % state.searchMatchIndices.size
        handle.update {
            search = search.copy(
                currentSearchMatchIndex = nextIndex,
                searchFocusRequest = focusRequestFor(search.searchMatchIndices[nextIndex]),
            )
        }
    }

    fun previousSearchMatch() {
        val state = handle.state
        if (state.searchMatchIndices.isEmpty()) return
        val prevIndex = if (state.currentSearchMatchIndex > 0) {
            state.currentSearchMatchIndex - 1
        } else {
            state.searchMatchIndices.size - 1
        }
        handle.update {
            search = search.copy(
                currentSearchMatchIndex = prevIndex,
                searchFocusRequest = focusRequestFor(search.searchMatchIndices[prevIndex]),
            )
        }
    }

    fun onSearchScrollHandled() {
        handle.update { search = search.copy(searchFocusRequest = null) }
    }

    private fun focusRequestFor(match: SearchMatch) = SearchFocusRequest(
        messageIndex = match.messageIndex,
        requestId = nextFocusRequestId++,
    )
}
