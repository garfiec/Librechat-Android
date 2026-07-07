package com.garfiec.librechat.navigation

import android.net.Uri
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import co.touchlab.kermit.Logger
import com.garfiec.librechat.feature.chat.navigation.Chat
import com.garfiec.librechat.feature.chat.navigation.NewChat
import com.garfiec.librechat.shared.navigation.DeepLinkResolution
import com.garfiec.librechat.shared.navigation.DeepLinks
import com.garfiec.librechat.shared.navigation.PhoneLayout
import com.garfiec.librechat.shared.navigation.LibreChatNavHost as SharedLibreChatNavHost

/**
 * Android-specific entry point that wraps the shared [SharedLibreChatNavHost]
 * with deep link handling, share intent support, and tablet layout branching.
 */
@Composable
fun LibreChatNavHost(
    modifier: Modifier = Modifier,
    windowSizeClass: WindowSizeClass? = null,
    deepLinkUri: Uri? = null,
    onDeepLinkConsume: () -> Unit = {},
    shareNavigationTrigger: Int = 0,
    appLocaleTag: String? = null,
) {
    // Resolve the pending link once (both the auth-suppression flag and the handler below use it).
    val resolution = remember(deepLinkUri) { deepLinkUri?.let { DeepLinks.resolve(it.toDeepLinkUri()) } }

    SharedLibreChatNavHost(
        modifier = modifier,
        appLocaleTag = appLocaleTag,
        // Cold-start deep links are set before setContent, so this is true on the first composition
        // when a link is pending that is *valid without auth* — the shared host then skips its initial
        // auth redirect and lets the handler below own the stack (so a logged-out target isn't wiped by
        // an auth clear). Auth-required and non-routable links must NOT suppress it: the former should
        // land on login, the latter would strand a logged-out user on an un-authed screen.
        hasPendingDeepLink = resolution is DeepLinkResolution.Route && !resolution.requiresAuth,
    ) { navigator, navHostViewModel, mod ->
        // Handle deep links
        val currentOnDeepLinkConsume by rememberUpdatedState(onDeepLinkConsume)
        LaunchedEffect(deepLinkUri) {
            val uri = deepLinkUri ?: return@LaunchedEffect
            when (resolution) {
                is DeepLinkResolution.Route -> {
                    val target = resolution.target
                    val loggedIn = navHostViewModel.isLoggedIn.value
                    when {
                        // Auth-required target while logged out → login. (Resuming to the target
                        // after login is a follow-up; this at least never lands on a broken screen.)
                        resolution.requiresAuth && !loggedIn -> navigator.navigateToAuth()
                        // Open-logged-out target (device-scoped artifact) sits atop an auth base so
                        // backing out lands on login; rebuilds the stack so repeat taps can't pile up.
                        !loggedIn -> navigator.navigateToDeepLinkLoggedOut(target)
                        // Switching chats replaces the current chat entry so back returns to NewChat.
                        target is Chat -> target.conversationId?.let { navigator.navigateToChat(it) }
                        // Model shortcut: NewChat carries an endpoint/model payload. Go through top-level
                        // nav so it replaces a bare landing NewChat (dedup-by-value) and re-seeds even
                        // when already on the landing.
                        target is NewChat -> navigator.navigateToTopLevel(target)
                        // Otherwise push, de-duping a repeat tap of the same target.
                        navigator.currentRoute != target -> navigator.navigate(target)
                    }
                }
                // OAuth redirect: the login screen consumes the refresh-token cookie; nothing to route.
                DeepLinkResolution.Consumed, null -> Unit
                DeepLinkResolution.None -> Logger.w { "Ignoring non-routable deep link: $uri" }
            }
            currentOnDeepLinkConsume()
        }

        // Handle share intent
        LaunchedEffect(shareNavigationTrigger) {
            if (shareNavigationTrigger > 0) {
                val currentRoute = navigator.currentRoute
                if (currentRoute !is Chat && currentRoute !is NewChat) {
                    navigator.navigateToTopLevel(NewChat())
                }
            }
        }

        val isTablet = windowSizeClass?.widthSizeClass?.let {
            it >= WindowWidthSizeClass.Medium
        } ?: false

        if (isTablet) {
            TabletLayout(
                navigator = navigator,
                navHostViewModel = navHostViewModel,
                modifier = mod,
            )
        } else {
            PhoneLayout(
                navigator = navigator,
                modifier = mod,
            )
        }
    }
}
