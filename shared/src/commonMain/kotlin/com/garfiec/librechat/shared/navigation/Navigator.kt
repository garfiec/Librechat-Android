package com.garfiec.librechat.shared.navigation

import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.garfiec.librechat.feature.auth.navigation.AuthRoute
import com.garfiec.librechat.feature.auth.navigation.ServerUrl
import com.garfiec.librechat.feature.auth.navigation.isAddAccountFlowRoute
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

    /** Navigate to a chat, replacing any current chat on the stack. [isTemporary] marks a
     *  temporary chat so a restored Chat(id) entry stays temp-aware and never persists
     *  the server-hidden conversation to Room — see [Chat.isTemporary]. */
    fun navigateToChat(conversationId: String, isTemporary: Boolean = false) {
        if (backStack.lastOrNull() is Chat) {
            backStack.removeLastOrNull()
        }
        backStack.add(Chat(conversationId, isTemporary))
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

    /**
     * Clear back stack and navigate to auth (session expiry / logout).
     *
     * No-ops when already in the auth flow, matching the dedupe its siblings above do. A dead session
     * is reported by more than one caller (a cold start fans out several requests and each 401 settles
     * independently), and a straggler landing after the user has moved on to Login/Register/2FA would
     * otherwise reset them to [ServerUrl] and discard what they had typed.
     */
    fun navigateToAuth() {
        // An add-account flow is not "already at the destination": it belongs to the session that
        // just ended, and the nav host cancels the pending add by watching those routes leave the
        // stack — so skipping the reset there would strand the flow with a dead parent session.
        if (isInAuthFlow && backStack.none { it.isAddAccountFlowRoute }) return
        backStack.clear()
        backStack.add(ServerUrl)
    }

    /**
     * Cold-start deep link handled while logged out: show [route] atop an auth base, so the target
     * (e.g. a device-scoped artifact shortcut, viewable logged out) is reached deterministically and
     * backing out of it lands on login rather than a half-initialized logged-out screen. The shared
     * host's initial auth redirect is skipped when a deep link is pending, so this owns that setup.
     */
    fun navigateToDeepLinkLoggedOut(route: NavKey) {
        backStack.clear()
        backStack.add(ServerUrl)
        backStack.add(route)
    }

    /** Clear back stack and navigate to chat (auth complete). */
    fun navigateToChat() {
        backStack.clear()
        backStack.add(NewChat())
    }
}
