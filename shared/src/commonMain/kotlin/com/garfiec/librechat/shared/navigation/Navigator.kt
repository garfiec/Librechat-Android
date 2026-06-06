package com.garfiec.librechat.shared.navigation

import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.garfiec.librechat.feature.auth.navigation.AuthRoute
import com.garfiec.librechat.feature.auth.navigation.ServerUrl
import com.garfiec.librechat.feature.chat.navigation.Chat
import com.garfiec.librechat.feature.chat.navigation.NewChat
import com.garfiec.librechat.feature.settings.navigation.ProviderKeys

/**
 * Encapsulates all back stack mutations for Nav 3 navigation.
 * Follows UDF: UI events → Navigator → back stack state → NavDisplay recomposition.
 */
class Navigator(val backStack: NavBackStack<NavKey>) {

    val currentRoute: NavKey? get() = backStack.lastOrNull()

    val isInAuthFlow: Boolean get() = currentRoute is AuthRoute

    /** Navigate forward to a route. */
    fun navigate(route: NavKey) {
        backStack.add(route)
    }

    /** Pop the top entry. Safe on empty stacks. */
    fun goBack() {
        backStack.removeLastOrNull()
    }

    /** Navigate to a chat, replacing any current chat on the stack. */
    fun navigateToChat(conversationId: String) {
        if (backStack.lastOrNull() is Chat) {
            backStack.removeLastOrNull()
        }
        backStack.add(Chat(conversationId))
    }

    /**
     * Navigate to Provider API Keys with optional auto-open dialog. Dedupes when the
     * top of the stack is already [ProviderKeys]: no-op when the pending endpoint is
     * identical, otherwise replace so the dialog refreshes for a different endpoint.
     */
    fun navigateToProviderKeys(endpointName: String?) {
        val top = backStack.lastOrNull()
        if (top is ProviderKeys) {
            if (top.pendingDialogEndpoint == endpointName) return
            backStack.removeLastOrNull()
        }
        backStack.add(ProviderKeys(pendingDialogEndpoint = endpointName))
    }

    /** Navigate to a top-level destination, popping to root first. Dedupes by VALUE
     *  (not class) so a payload-carrying route replaces a same-class root that differs:
     *  e.g. NewChat("agent_X") must replace the bare landing NewChat() so the agent id
     *  actually reaches the chat — a class-only check silently dropped it. Re-selecting
     *  an identical route (data-object tabs, or NewChat()→NewChat()) still no-ops. */
    fun navigateToTopLevel(route: NavKey) {
        while (backStack.size > 1) {
            backStack.removeLastOrNull()
        }
        if (backStack.lastOrNull() != route) {
            backStack.removeLastOrNull()
            backStack.add(route)
        }
    }

    /** Clear back stack and navigate to auth (session expiry / logout). */
    fun navigateToAuth() {
        backStack.clear()
        backStack.add(ServerUrl)
    }

    /** Clear back stack and navigate to chat (auth complete). */
    fun navigateToChat() {
        backStack.clear()
        backStack.add(NewChat())
    }
}
