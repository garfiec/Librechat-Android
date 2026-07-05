package com.garfiec.librechat.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.garfiec.librechat.core.model.Banner
import com.garfiec.librechat.core.ui.components.BannerDisplay
import com.garfiec.librechat.feature.agents.navigation.AgentMarketplace
import com.garfiec.librechat.feature.auth.navigation.AddAccountServerUrl
import com.garfiec.librechat.feature.chat.navigation.NewChat
import com.garfiec.librechat.feature.conversations.navigation.Projects
import com.garfiec.librechat.feature.files.navigation.Files
import com.garfiec.librechat.feature.settings.navigation.SettingsTabbed
import com.garfiec.librechat.feature.skills.navigation.SkillsList
import com.garfiec.librechat.shared.navigation.MainNavDisplay
import com.garfiec.librechat.shared.navigation.NavHostViewModel
import com.garfiec.librechat.shared.navigation.Navigator
import com.garfiec.librechat.shared.navigation.SidebarMode
import com.garfiec.librechat.shared.navigation.SidebarScaffold
import com.garfiec.librechat.shared.navigation.toRoute
import kotlinx.coroutines.launch

private val SidebarWidth = 320.dp

/** Velocity (px/s) above which a fling commits the gesture regardless of position. */
private const val FLING_VELOCITY_THRESHOLD = 800f

@Composable
fun TabletLayout(
    navigator: Navigator,
    navHostViewModel: NavHostViewModel,
    modifier: Modifier = Modifier,
) {
    // Banner state only -- drawer state is collected inside DrawerContent itself
    val banners by navHostViewModel.banners.collectAsStateWithLifecycle()
    val dismissedBannerIds by navHostViewModel.dismissedBannerIds.collectAsStateWithLifecycle()

    // Persisted sidebar state from DataStore -- single source of truth in the ViewModel.
    // Null until the persisted value resolves; treat unknown as closed for boolean callers.
    val resolvedSidebarOpen by navHostViewModel.tabletSidebarOpen.collectAsStateWithLifecycle()
    val isSidebarOpen = resolvedSidebarOpen == true

    // Whether swipe gesture is enabled (from settings)
    val gestureEnabled by navHostViewModel.tabletSidebarGestureEnabled.collectAsStateWithLifecycle()

    val density = LocalDensity.current
    val sidebarWidthPx = with(density) { SidebarWidth.toPx() }

    // Animatable tracks sidebar reveal in pixels: 0 = closed, sidebarWidthPx = open.
    val sidebarOffset = remember { Animatable(0f) }
    var initialStateApplied by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Back press closes sidebar before navigating away
    BackHandler(enabled = isSidebarOpen) {
        navHostViewModel.setTabletSidebarOpen(false)
    }

    // Reset the sidebar to Conversations whenever it's closed, so reopening lands on recents and
    // the Projects-mode back handler (in SidebarScaffold) can't stay armed while the sidebar is
    // hidden off-screen and swallow back presses. Mirrors PhoneLayout's drawerState.isClosed reset.
    LaunchedEffect(isSidebarOpen) {
        if (!isSidebarOpen) {
            navHostViewModel.setSidebarMode(SidebarMode.Conversations)
        }
    }

    // Sync: when ViewModel state changes (e.g. hamburger button, back press), animate the
    // sidebar to match. The first resolved value is snapped (not animated) so a tablet that
    // restores "open" doesn't visibly slide the sidebar in on every cold start.
    LaunchedEffect(resolvedSidebarOpen) {
        val resolved = resolvedSidebarOpen ?: return@LaunchedEffect
        val target = if (resolved) sidebarWidthPx else 0f
        if (!initialStateApplied) {
            sidebarOffset.snapTo(target)
            initialStateApplied = true
        } else if (sidebarOffset.value != target) {
            sidebarOffset.animateTo(
                targetValue = target,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
            )
        }
    }

    if (!navigator.isInAuthFlow) {
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
                        onNewChat = {
                            if (navigator.currentRoute !is NewChat) {
                                navigator.navigateToTopLevel(NewChat())
                            }
                        },
                        onConversationClick = { conversationId ->
                            navigator.navigateToChat(conversationId)
                        },
                        onSettingsClick = {
                            navigator.navigate(SettingsTabbed)
                        },
                        onSettingsCategorySelect = { category ->
                            navigator.navigate(category.toRoute())
                        },
                        onAgentsClick = {
                            navigator.navigate(AgentMarketplace)
                        },
                        onFilesClick = {
                            navigator.navigate(Files)
                        },
                        onSkillsClick = {
                            navigator.navigate(SkillsList)
                        },
                        onOpenProjectsIndex = {
                            navigator.navigate(Projects)
                        },
                        onSwitchAccount = { accountId ->
                            navHostViewModel.switchAccount(accountId)
                        },
                        onAddAccount = {
                            navigator.navigate(AddAccountServerUrl)
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
                    navigator = navigator,
                    isInAuthFlow = false,
                    banners = banners,
                    dismissedBannerIds = dismissedBannerIds,
                    onDismissBanner = navHostViewModel::dismissBanner,
                    onToggleDrawer = {
                        navHostViewModel.toggleTabletSidebar()
                    },
                )
            },
            modifier = modifier.fillMaxSize().then(swipeModifier).clipToBounds(),
        ) { measurables, constraints ->
            val sidebarPx = SidebarWidth.roundToPx()
            val animatedPx = sidebarOffset.value.toInt()

            val sidebarPlaceable = measurables[0].measure(
                constraints.copy(minWidth = sidebarPx, maxWidth = sidebarPx),
            )
            val mainWidth = (constraints.maxWidth - animatedPx).coerceAtLeast(0)
            val mainPlaceable = measurables[1].measure(
                constraints.copy(minWidth = mainWidth, maxWidth = mainWidth),
            )

            layout(constraints.maxWidth, constraints.maxHeight) {
                sidebarPlaceable.placeRelative(animatedPx - sidebarPx, 0)
                mainPlaceable.placeRelative(animatedPx, 0)
            }
        }
    } else {
        // Auth flow -- no sidebar, just main content
        MainContent(
            navigator = navigator,
            isInAuthFlow = true,
            banners = banners,
            dismissedBannerIds = dismissedBannerIds,
            onDismissBanner = navHostViewModel::dismissBanner,
            onToggleDrawer = {},
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun MainContent(
    navigator: Navigator,
    isInAuthFlow: Boolean,
    banners: List<Banner>,
    dismissedBannerIds: Set<String>,
    onDismissBanner: (String) -> Unit,
    onToggleDrawer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        if (!isInAuthFlow && banners.isNotEmpty()) {
            BannerDisplay(
                banners = banners,
                dismissedIds = dismissedBannerIds,
                onDismiss = onDismissBanner,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        MainNavDisplay(
            navigator = navigator,
            onMenuClick = onToggleDrawer,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
