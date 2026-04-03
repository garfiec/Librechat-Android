package com.librechat.android.navigation

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideOut
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import com.librechat.android.MainActivity
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
import com.librechat.android.feature.settings.navigation.SETTINGS_TABBED_ROUTE
import com.librechat.android.feature.settings.navigation.SHARED_LINKS_ROUTE
import com.librechat.android.feature.settings.navigation.settingsGraph
import com.librechat.android.feature.voice.navigation.navigateToVoiceHome
import com.librechat.android.feature.voice.navigation.voiceGraph
import kotlinx.coroutines.launch
import timber.log.Timber

private val SidebarWidth = 320.dp

/** Velocity (px/s) above which a fling commits the gesture regardless of position. */
private const val FLING_VELOCITY_THRESHOLD = 800f

@Composable
fun TabletLayout(
    navController: NavHostController,
    navHostViewModel: NavHostViewModel,
    startDestination: String,
    isInAuthFlow: Boolean,
    deepLinkUri: Uri?,
    onDeepLinkConsumed: () -> Unit,
    modifier: Modifier = Modifier,
    shareNavigationTrigger: Int = 0,
) {
    // Banner state only -- drawer state is collected inside DrawerContent itself
    val banners by navHostViewModel.banners.collectAsStateWithLifecycle()
    val dismissedBannerIds by navHostViewModel.dismissedBannerIds.collectAsStateWithLifecycle()

    // Persisted sidebar state from DataStore -- single source of truth in the ViewModel.
    // The ViewModel's StateFlow initial value is read synchronously from DataStore,
    // so the first composition already has the correct persisted state (no flicker).
    val isSidebarOpen by navHostViewModel.tabletSidebarOpen.collectAsStateWithLifecycle()

    // Whether swipe gesture is enabled (from settings)
    val gestureEnabled by navHostViewModel.tabletSidebarGestureEnabled.collectAsStateWithLifecycle()

    val density = LocalDensity.current
    val sidebarWidthPx = with(density) { SidebarWidth.toPx() }

    // Animatable tracks sidebar reveal in pixels: 0 = closed, sidebarWidthPx = open.
    // During a drag it follows the finger; on release it animates to 0 or sidebarWidthPx.
    val sidebarOffset = remember { Animatable(if (isSidebarOpen) sidebarWidthPx else 0f) }
    val scope = rememberCoroutineScope()

    // Back press closes sidebar before navigating away
    BackHandler(enabled = isSidebarOpen) {
        navHostViewModel.setTabletSidebarOpen(false)
    }

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
        }
    }

    // Sync: when ViewModel state changes (e.g. hamburger button, back press),
    // animate the sidebar to match.
    LaunchedEffect(isSidebarOpen) {
        val target = if (isSidebarOpen) sidebarWidthPx else 0f
        if (sidebarOffset.value != target) {
            sidebarOffset.animateTo(
                targetValue = target,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
            )
        }
    }

    if (!isInAuthFlow) {
        val swipeModifier = if (gestureEnabled) {
            Modifier.pointerInput(Unit) {
                val velocityTracker = VelocityTracker()
                detectHorizontalDragGestures(
                    onDragStart = {
                        velocityTracker.resetTracking()
                    },
                    onDragEnd = {
                        val velocity = velocityTracker.calculateVelocity().x
                        val currentOffset = sidebarOffset.value
                        // Decide target: fling velocity wins, otherwise use 50% threshold
                        val shouldOpen = when {
                            velocity > FLING_VELOCITY_THRESHOLD -> true
                            velocity < -FLING_VELOCITY_THRESHOLD -> false
                            else -> currentOffset > sidebarWidthPx * 0.5f
                        }
                        scope.launch {
                            sidebarOffset.animateTo(
                                targetValue = if (shouldOpen) sidebarWidthPx else 0f,
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                    stiffness = Spring.StiffnessMediumLow,
                                ),
                            )
                            navHostViewModel.setTabletSidebarOpen(shouldOpen)
                        }
                    },
                    onDragCancel = {
                        // Snap back to the current committed state
                        val target = if (isSidebarOpen) sidebarWidthPx else 0f
                        scope.launch {
                            sidebarOffset.animateTo(
                                targetValue = target,
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                    stiffness = Spring.StiffnessMediumLow,
                                ),
                            )
                        }
                    },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        velocityTracker.addPosition(
                            change.uptimeMillis,
                            change.position,
                        )
                        scope.launch {
                            val newOffset = (sidebarOffset.value + dragAmount)
                                .coerceIn(0f, sidebarWidthPx)
                            sidebarOffset.snapTo(newOffset)
                        }
                    },
                )
            }
        } else {
            Modifier
        }

        Layout(
            content = {
                // Slot 0: Sidebar -- always 320dp, slides from off-screen left to x=0
                Row(modifier = Modifier.fillMaxHeight()) {
                    SidebarScaffold(
                        viewModel = navHostViewModel,
                        onNewChat = {
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
                            navController.navigateToChat(conversationId)
                        },
                        onSettingsClick = {
                            navController.navigate(SETTINGS_TABBED_ROUTE) {
                                launchSingleTop = true
                            }
                        },
                        onSettingsCategorySelected = { category ->
                            navController.navigate(category.toRoute()) {
                                launchSingleTop = true
                            }
                        },
                        onAgentsClick = {
                            navController.navigate(TopLevelDestination.AGENTS.route) {
                                launchSingleTop = true
                            }
                        },
                        onFilesClick = {
                            navController.navigate(TopLevelDestination.FILES.route) {
                                launchSingleTop = true
                            }
                        },
                        onVoiceClick = {
                            navController.navigateToVoiceHome()
                        },
                        modifier = Modifier
                            .width(SidebarWidth)
                            .fillMaxHeight(),
                    )
                    VerticalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
                // Slot 1: Main content -- resizes to fill remaining space
                MainContent(
                    navController = navController,
                    navHostViewModel = navHostViewModel,
                    startDestination = startDestination,
                    isInAuthFlow = false,
                    banners = banners,
                    dismissedBannerIds = dismissedBannerIds,
                    onToggleDrawer = {
                        navHostViewModel.setTabletSidebarOpen(!isSidebarOpen)
                    },
                )
            },
            modifier = modifier.fillMaxSize().then(swipeModifier).clipToBounds(),
        ) { measurables, constraints ->
            val sidebarPx = SidebarWidth.roundToPx()
            val animatedPx = sidebarOffset.value.toInt()

            // Sidebar: always measured at full 320dp width
            val sidebarPlaceable = measurables[0].measure(
                constraints.copy(minWidth = sidebarPx, maxWidth = sidebarPx),
            )
            // Main content: resizes to fill screen minus the visible sidebar portion
            val mainWidth = (constraints.maxWidth - animatedPx).coerceAtLeast(0)
            val mainPlaceable = measurables[1].measure(
                constraints.copy(minWidth = mainWidth, maxWidth = mainWidth),
            )

            layout(constraints.maxWidth, constraints.maxHeight) {
                // Sidebar slides: -320px (off-screen) -> 0px (fully visible)
                sidebarPlaceable.placeRelative(animatedPx - sidebarPx, 0)
                // Main content starts right after the visible sidebar portion
                mainPlaceable.placeRelative(animatedPx, 0)
            }
        }
    } else {
        // Auth flow -- no sidebar, just main content
        MainContent(
            navController = navController,
            navHostViewModel = navHostViewModel,
            startDestination = startDestination,
            isInAuthFlow = true,
            banners = banners,
            dismissedBannerIds = dismissedBannerIds,
            onToggleDrawer = {},
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun MainContent(
    navController: NavHostController,
    navHostViewModel: NavHostViewModel,
    startDestination: String,
    isInAuthFlow: Boolean,
    banners: List<com.librechat.android.core.model.Banner>,
    dismissedBannerIds: Set<String>,
    onToggleDrawer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
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
                onOpenDrawer = onToggleDrawer,
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
