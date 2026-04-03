package com.librechat.android.navigation

import android.net.Uri
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideOut
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.librechat.android.MainActivity
import com.librechat.android.R
import com.librechat.android.core.ui.components.BannerDisplay
import com.librechat.android.feature.agents.navigation.AGENT_EDITOR_CREATE_ROUTE
import com.librechat.android.feature.agents.navigation.agentsGraph
import com.librechat.android.feature.auth.navigation.AUTH_GRAPH_ROUTE
import com.librechat.android.feature.auth.navigation.authGraph
import com.librechat.android.feature.chat.navigation.CHAT_GRAPH_ROUTE
import com.librechat.android.feature.chat.navigation.CHAT_ROUTE
import com.librechat.android.feature.chat.navigation.NEW_CHAT_ROUTE
import com.librechat.android.feature.chat.navigation.chatGraph
import com.librechat.android.feature.chat.navigation.navigateToChat
import com.librechat.android.feature.conversations.navigation.conversationsGraph
import com.librechat.android.feature.files.navigation.filesGraph
import com.librechat.android.feature.settings.navigation.API_KEYS_ROUTE
import com.librechat.android.feature.settings.navigation.PRESET_MANAGER_ROUTE
import com.librechat.android.feature.settings.navigation.SETTINGS_ACCOUNT_ROUTE
import com.librechat.android.feature.settings.navigation.SETTINGS_CHAT_ROUTE
import com.librechat.android.feature.settings.navigation.SETTINGS_DATA_ROUTE
import com.librechat.android.feature.settings.navigation.SETTINGS_GENERAL_ROUTE
import com.librechat.android.feature.settings.navigation.SETTINGS_TABBED_ROUTE
import com.librechat.android.feature.settings.navigation.SHARED_LINKS_ROUTE
import com.librechat.android.feature.settings.navigation.settingsGraph
import com.librechat.android.feature.voice.navigation.navigateToVoiceHome
import com.librechat.android.feature.voice.navigation.voiceGraph
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel
import timber.log.Timber

/** Maps a [SettingsCategory] to its corresponding navigation route. */
fun SettingsCategory.toRoute(): String = when (this) {
    SettingsCategory.GENERAL -> SETTINGS_GENERAL_ROUTE
    SettingsCategory.CHAT -> SETTINGS_CHAT_ROUTE
    SettingsCategory.ACCOUNT -> SETTINGS_ACCOUNT_ROUTE
    SettingsCategory.DATA -> SETTINGS_DATA_ROUTE
}

@Composable
fun LibreChatNavHost(
    modifier: Modifier = Modifier,
    windowSizeClass: WindowSizeClass? = null,
    deepLinkUri: Uri? = null,
    onDeepLinkConsumed: () -> Unit = {},
    shareNavigationTrigger: Int = 0,
    navHostViewModel: NavHostViewModel = koinViewModel(),
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    val isLoggedIn by navHostViewModel.isLoggedIn.collectAsStateWithLifecycle()

    val isInAuthFlow = currentDestination?.route == AUTH_GRAPH_ROUTE ||
        currentDestination?.parent?.route == AUTH_GRAPH_ROUTE

    // Track active conversation from nav back stack
    LaunchedEffect(navBackStackEntry) {
        val route = navBackStackEntry?.destination?.route
        val conversationId = if (route == CHAT_ROUTE) {
            navBackStackEntry?.arguments?.getString("conversationId")
        } else {
            null
        }
        navHostViewModel.setActiveConversation(conversationId)
    }

    // Handle session expiry
    LaunchedEffect(Unit) {
        navHostViewModel.sessionExpired.collect {
            navController.navigate(AUTH_GRAPH_ROUTE) {
                popUpTo(0) { inclusive = true }
            }
        }
    }

    val startDestination = if (isLoggedIn) CHAT_GRAPH_ROUTE else AUTH_GRAPH_ROUTE

    val isTablet = windowSizeClass?.widthSizeClass?.let {
        it >= WindowWidthSizeClass.Medium
    } ?: false

    if (isTablet) {
        TabletLayout(
            navController = navController,
            navHostViewModel = navHostViewModel,
            startDestination = startDestination,
            isInAuthFlow = isInAuthFlow,
            deepLinkUri = deepLinkUri,
            onDeepLinkConsumed = onDeepLinkConsumed,
            shareNavigationTrigger = shareNavigationTrigger,
            modifier = modifier,
        )
    } else {
        PhoneLayout(
            navController = navController,
            navHostViewModel = navHostViewModel,
            startDestination = startDestination,
            isInAuthFlow = isInAuthFlow,
            deepLinkUri = deepLinkUri,
            onDeepLinkConsumed = onDeepLinkConsumed,
            shareNavigationTrigger = shareNavigationTrigger,
            modifier = modifier,
        )
    }

    // Version mismatch warning dialog (shown over any layout)
    val versionMismatch by navHostViewModel.versionMismatch.collectAsStateWithLifecycle()
    versionMismatch?.let { mismatch ->
        VersionMismatchDialog(
            supportedVersion = mismatch.supportedVersion,
            backendVersion = mismatch.backendVersion,
            onDismiss = navHostViewModel::dismissVersionWarning,
            onDismissPermanently = navHostViewModel::dismissVersionWarningPermanently,
        )
    }
}

/**
 * Dialog shown when the backend server version does not match the version
 * this app was built for. Offers "Dismiss" (session-only) and
 * "Don't warn again" (persisted per backend version) options.
 */
@Composable
private fun VersionMismatchDialog(
    supportedVersion: String,
    backendVersion: String,
    onDismiss: () -> Unit,
    onDismissPermanently: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.version_mismatch_title),
                style = MaterialTheme.typography.headlineSmall,
            )
        },
        text = {
            Text(
                text = stringResource(R.string.version_mismatch_message, supportedVersion, backendVersion),
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.dismiss))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissPermanently) {
                Text(stringResource(R.string.dont_warn_again))
            }
        },
    )
}

@Composable
private fun PhoneLayout(
    navController: androidx.navigation.NavHostController,
    navHostViewModel: NavHostViewModel,
    startDestination: String,
    isInAuthFlow: Boolean,
    deepLinkUri: Uri?,
    onDeepLinkConsumed: () -> Unit,
    shareNavigationTrigger: Int,
    modifier: Modifier = Modifier,
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    // Banner state only -- drawer state is collected inside DrawerContent itself
    val banners by navHostViewModel.banners.collectAsStateWithLifecycle()
    val dismissedBannerIds by navHostViewModel.dismissedBannerIds.collectAsStateWithLifecycle()

    // Handle deep links
    LaunchedEffect(deepLinkUri) {
        deepLinkUri?.let { uri ->
            if (uri.scheme == "librechat" && uri.host == "conversation") {
                uri.lastPathSegment?.let { conversationId ->
                    if (MainActivity.CONVERSATION_ID_REGEX.matches(conversationId)) {
                        navController.navigateToChat(conversationId)
                    } else {
                        Timber.w("Ignoring deep link with invalid conversation ID: %s", conversationId)
                    }
                }
            }
            onDeepLinkConsumed()
        }
    }

    // Handle share intent: if already on a chat screen, let the active ChatViewModel
    // consume the shared content reactively. Only navigate to new chat when on a
    // non-chat screen (e.g. settings, agents, files).
    LaunchedEffect(shareNavigationTrigger) {
        if (shareNavigationTrigger > 0) {
            val currentRoute = navController.currentBackStackEntry?.destination?.route
            val isOnChatScreen = currentRoute == CHAT_ROUTE || currentRoute == NEW_CHAT_ROUTE
            if (!isOnChatScreen) {
                navController.navigate(NEW_CHAT_ROUTE) {
                    popUpTo(CHAT_GRAPH_ROUTE) { inclusive = false }
                    launchSingleTop = true
                }
            }
            // If already on a chat screen, SharedIntentConsumer.shareAvailable
            // will notify the active ChatViewModel to consume the content.
        }
    }

    // Reset sidebar mode to Conversations when the drawer closes
    LaunchedEffect(drawerState.isClosed) {
        if (drawerState.isClosed) {
            navHostViewModel.setSidebarMode(SidebarMode.Conversations)
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = !isInAuthFlow,
        drawerContent = {
            ModalDrawerSheet {
                SidebarScaffold(
                    viewModel = navHostViewModel,
                    onNewChat = {
                        scope.launch { drawerState.close() }
                        // Skip navigation if already on the new chat screen
                        val currentRoute = navController.currentBackStackEntry?.destination?.route
                        if (currentRoute != NEW_CHAT_ROUTE) {
                            navController.navigate(NEW_CHAT_ROUTE) {
                                popUpTo(CHAT_GRAPH_ROUTE) { inclusive = false }
                                launchSingleTop = true
                            }
                        }
                    },
                    onConversationClick = { conversationId ->
                        scope.launch { drawerState.close() }
                        navController.navigateToChat(conversationId)
                    },
                    onSettingsClick = {
                        scope.launch { drawerState.close() }
                        navController.navigate(SETTINGS_TABBED_ROUTE) {
                            launchSingleTop = true
                        }
                    },
                    onSettingsCategorySelected = { category ->
                        // Navigate to the settings sub-page but keep the drawer open
                        navController.navigate(category.toRoute()) {
                            launchSingleTop = true
                        }
                    },
                    onAgentsClick = {
                        scope.launch { drawerState.close() }
                        navController.navigate(TopLevelDestination.AGENTS.route) {
                            launchSingleTop = true
                        }
                    },
                    onFilesClick = {
                        scope.launch { drawerState.close() }
                        navController.navigate(TopLevelDestination.FILES.route) {
                            launchSingleTop = true
                        }
                    },
                    onVoiceClick = {
                        scope.launch { drawerState.close() }
                        navController.navigateToVoiceHome()
                    },
                )
            }
        },
    ) {
        // No bottom bar -- drawer is the primary navigation
        Column(modifier = modifier.fillMaxSize()) {
            if (!isInAuthFlow && banners.isNotEmpty()) {
                BannerDisplay(
                    banners = banners,
                    dismissedIds = dismissedBannerIds,
                    onDismiss = navHostViewModel::dismissBanner,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            NavHost(
                navController = navController,
                startDestination = startDestination,
                modifier = Modifier.fillMaxSize(),
                enterTransition = {
                    slideIntoContainer(
                        towards = AnimatedContentTransitionScope.SlideDirection.Start,
                        animationSpec = tween(300),
                    )
                },
                exitTransition = {
                    fadeOut(animationSpec = tween(150))
                },
                popEnterTransition = {
                    fadeIn(
                        initialAlpha = 0.5f,
                        animationSpec = tween(300, easing = LinearEasing),
                    ) + scaleIn(
                        initialScale = 0.92f,
                        animationSpec = tween(300, easing = LinearEasing),
                    )
                },
                popExitTransition = {
                    slideOut(
                        targetOffset = { IntOffset((it.width * 0.15f).toInt(), 0) },
                        animationSpec = tween(300, easing = FastOutSlowInEasing),
                    ) + scaleOut(
                        targetScale = 0.92f,
                        animationSpec = tween(300, easing = LinearEasing),
                    )
                },
            ) {
                authGraph(
                    navController = navController,
                    onAuthComplete = {
                        navHostViewModel.onAuthComplete()
                        navController.navigate(CHAT_GRAPH_ROUTE) {
                            popUpTo(AUTH_GRAPH_ROUTE) { inclusive = true }
                        }
                    },
                )
                chatGraph(
                    navController = navController,
                    onOpenDrawer = {
                        scope.launch { drawerState.open() }
                    },
                )
                conversationsGraph(
                    onConversationClick = { conversationId ->
                        navController.navigateToChat(conversationId)
                    },
                    onNavigateToArchived = {
                        navController.navigate("conversations/archived")
                    },
                    onNavigateBackFromArchived = {
                        navController.popBackStack()
                    },
                )
                agentsGraph(
                    onAgentClick = { agentId ->
                        navController.navigate("agents/$agentId")
                    },
                    onBack = { navController.popBackStack() },
                    onStartChat = { agentId ->
                        navController.navigate(NEW_CHAT_ROUTE) {
                            popUpTo(CHAT_GRAPH_ROUTE) { inclusive = false }
                            launchSingleTop = true
                        }
                    },
                    onCreateAgent = {
                        navController.navigate(AGENT_EDITOR_CREATE_ROUTE)
                    },
                    onEditAgent = { agentId ->
                        navController.navigate("agents/editor/$agentId")
                    },
                )
                filesGraph()
                voiceGraph(navController)
                settingsGraph(
                    onLogout = {
                        navHostViewModel.logout()
                        navController.navigate(AUTH_GRAPH_ROUTE) {
                            popUpTo(0) { inclusive = true }
                        }
                    },
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToArchived = {
                        navController.navigate("conversations/archived") {
                            launchSingleTop = true
                        }
                    },
                    onNavigateToSharedLinks = {
                        navController.navigate(SHARED_LINKS_ROUTE) {
                            launchSingleTop = true
                        }
                    },
                    onNavigateBackFromSharedLinks = {
                        navController.popBackStack()
                    },
                    onNavigateToPresets = {
                        navController.navigate(PRESET_MANAGER_ROUTE) {
                            launchSingleTop = true
                        }
                    },
                    onNavigateBackFromPresets = {
                        navController.popBackStack()
                    },
                    onNavigateToApiKeys = {
                        navController.navigate(API_KEYS_ROUTE) {
                            launchSingleTop = true
                        }
                    },
                    onNavigateBackFromApiKeys = {
                        navController.popBackStack()
                    },
                )
            }
        }
    }
}
