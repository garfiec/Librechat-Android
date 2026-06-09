package com.garfiec.librechat.navigation

import android.net.Uri
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import co.touchlab.kermit.Logger
import com.garfiec.librechat.MainActivity
import com.garfiec.librechat.feature.chat.navigation.Chat
import com.garfiec.librechat.feature.chat.navigation.NewChat
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
    SharedLibreChatNavHost(modifier = modifier, appLocaleTag = appLocaleTag) { navigator, navHostViewModel, mod ->
        // Handle deep links
        val currentOnDeepLinkConsume by rememberUpdatedState(onDeepLinkConsume)
        LaunchedEffect(deepLinkUri) {
            deepLinkUri?.let { uri ->
                if (uri.scheme == "librechat" && uri.host == "conversation") {
                    uri.lastPathSegment?.let { conversationId ->
                        if (MainActivity.CONVERSATION_ID_REGEX.matches(conversationId)) {
                            navigator.navigateToChat(conversationId)
                        } else {
                            Logger.w { "Ignoring deep link with invalid conversation ID: $conversationId" }
                        }
                    }
                }
                currentOnDeepLinkConsume()
            }
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
