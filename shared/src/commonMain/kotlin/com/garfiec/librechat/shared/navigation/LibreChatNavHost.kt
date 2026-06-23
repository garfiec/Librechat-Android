package com.garfiec.librechat.shared.navigation

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.garfiec.librechat.core.logging.Diag
import com.garfiec.librechat.core.ui.components.BannerDisplay
import com.garfiec.librechat.core.ui.theme.AppLocale
import com.garfiec.librechat.feature.agents.navigation.AgentMarketplace
import com.garfiec.librechat.feature.agents.navigation.agentsEntries
import com.garfiec.librechat.feature.auth.navigation.authEntries
import com.garfiec.librechat.feature.chat.navigation.Chat
import com.garfiec.librechat.feature.chat.navigation.NewChat
import com.garfiec.librechat.feature.chat.navigation.chatEntries
import com.garfiec.librechat.feature.chat.viewmodel.ServerFileSelectionHandoff
import com.garfiec.librechat.feature.conversations.navigation.ArchivedConversations
import com.garfiec.librechat.feature.conversations.navigation.conversationsEntries
import com.garfiec.librechat.feature.files.navigation.Files
import com.garfiec.librechat.feature.files.navigation.FilesPicker
import com.garfiec.librechat.feature.files.navigation.filePickerEntries
import com.garfiec.librechat.feature.files.navigation.filesEntries
import com.garfiec.librechat.feature.settings.navigation.SettingsTabbed
import com.garfiec.librechat.feature.settings.navigation.mcpServersEntry
import com.garfiec.librechat.feature.settings.navigation.memoriesEntry
import com.garfiec.librechat.feature.settings.navigation.settingsEntries
import com.garfiec.librechat.feature.skills.navigation.SkillsList
import com.garfiec.librechat.feature.skills.navigation.skillsEntries
import com.garfiec.librechat.shared.resources.Res
import com.garfiec.librechat.shared.resources.dismiss
import com.garfiec.librechat.shared.resources.dont_warn_again
import com.garfiec.librechat.shared.resources.version_mismatch_message
import com.garfiec.librechat.shared.resources.version_mismatch_title
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

/**
 * Shared root composable for LibreChat navigation.
 * Uses ModalNavigationDrawer (phone layout) for the sidebar-first pattern.
 *
 * Platform-specific features (deep links, tablet layout with BackHandler + swipe)
 * are handled by the Android app module. This shared version provides the core
 * phone layout that works on both Android and iOS.
 */
@Composable
fun LibreChatNavHost(
    modifier: Modifier = Modifier,
    appLocaleTag: String? = null,
    navHostViewModel: NavHostViewModel = koinViewModel(),
    content: (@Composable (Navigator, NavHostViewModel, Modifier) -> Unit)? = null,
) {
    val isLoggedIn by navHostViewModel.isLoggedIn.collectAsStateWithLifecycle()

    // Stable start key — auth redirect handled via LaunchedEffect below.
    val backStack = rememberNavBackStack(navigationSavedStateConfig, NewChat())
    val navigator = Navigator(backStack)

    // Redirect to auth if not logged in (on first composition only).
    LaunchedEffect(Unit) {
        if (!navHostViewModel.isLoggedIn.value) {
            navigator.navigateToAuth()
        }
    }

    // Track active conversation from nav back stack
    LaunchedEffect(navigator.currentRoute) {
        val conversationId = (navigator.currentRoute as? Chat)?.conversationId
        navHostViewModel.setActiveConversation(conversationId)

        // Navigation breadcrumb: route type name only — low cardinality, content-free.
        val screen = navigator.currentRoute?.let { it::class.simpleName } ?: "none"
        Diag.i("Breadcrumb", attrs = mapOf("screen" to screen)) { "nav" }
    }

    // Handle session expiry
    LaunchedEffect(Unit) {
        navHostViewModel.sessionExpired.collect {
            navigator.navigateToAuth()
        }
    }

    // The locale wrapper must sit BELOW the back stack: changing language recreates the rendered
    // UI so every stringResource re-resolves, while the back stack created above survives the swap
    // and the user stays on their current screen.
    AppLocale(tag = appLocaleTag) {
        if (content != null) {
            content(navigator, navHostViewModel, modifier)
        } else {
            PhoneLayout(
                navigator = navigator,
                modifier = modifier,
            )
        }

        // Version mismatch warning dialog
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
}

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
                text = stringResource(Res.string.version_mismatch_title),
                style = MaterialTheme.typography.headlineSmall,
            )
        },
        text = {
            Text(
                text = stringResource(Res.string.version_mismatch_message, supportedVersion, backendVersion),
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.dismiss))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissPermanently) {
                Text(stringResource(Res.string.dont_warn_again))
            }
        },
    )
}

/** Default phone layout with modal drawer sidebar. Used by iOS directly and by Android as the non-tablet path. */
@Composable
fun PhoneLayout(
    navigator: Navigator,
    modifier: Modifier = Modifier,
    navHostViewModel: NavHostViewModel = koinViewModel(),
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val banners by navHostViewModel.banners.collectAsStateWithLifecycle()
    val dismissedBannerIds by navHostViewModel.dismissedBannerIds.collectAsStateWithLifecycle()

    // Reset sidebar mode to Conversations when the drawer closes
    LaunchedEffect(drawerState.isClosed) {
        if (drawerState.isClosed) {
            navHostViewModel.setSidebarMode(SidebarMode.Conversations)
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = !navigator.isInAuthFlow,
        drawerContent = {
            ModalDrawerSheet {
                SidebarScaffold(
                    onNewChat = {
                        scope.launch { drawerState.close() }
                        if (navigator.currentRoute !is NewChat) {
                            navigator.navigateToTopLevel(NewChat())
                        }
                    },
                    onConversationClick = { conversationId ->
                        scope.launch { drawerState.close() }
                        navigator.navigateToChat(conversationId)
                    },
                    onSettingsClick = {
                        scope.launch { drawerState.close() }
                        navigator.navigate(SettingsTabbed)
                    },
                    onSettingsCategorySelect = { category ->
                        navigator.navigate(category.toRoute())
                    },
                    onAgentsClick = {
                        scope.launch { drawerState.close() }
                        navigator.navigate(AgentMarketplace)
                    },
                    onFilesClick = {
                        scope.launch { drawerState.close() }
                        navigator.navigate(Files)
                    },
                    onSkillsClick = {
                        scope.launch { drawerState.close() }
                        navigator.navigate(SkillsList)
                    },
                )
            }
        },
    ) {
        Column(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            if (!navigator.isInAuthFlow && banners.isNotEmpty()) {
                BannerDisplay(
                    banners = banners,
                    dismissedIds = dismissedBannerIds,
                    onDismiss = navHostViewModel::dismissBanner,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            MainNavDisplay(
                navigator = navigator,
                onMenuClick = { scope.launch { drawerState.open() } },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** Core NavDisplay with entry providers for all feature modules. Used by both PhoneLayout and Android's TabletLayout. */
@Composable
fun MainNavDisplay(
    navigator: Navigator,
    modifier: Modifier = Modifier,
    onMenuClick: (() -> Unit)? = null,
    navHostViewModel: NavHostViewModel = koinViewModel(),
    serverFileSelectionHandoff: ServerFileSelectionHandoff = koinInject(),
) {
    NavDisplay(
        backStack = navigator.backStack,
        onBack = { navigator.goBack() },
        modifier = modifier,
        transitionSpec = {
            slideInHorizontally(initialOffsetX = { it }, animationSpec = tween(300)) togetherWith
                fadeOut(animationSpec = tween(150))
        },
        popTransitionSpec = {
            (fadeIn(initialAlpha = 0.5f, animationSpec = tween(300, easing = LinearEasing)) +
                scaleIn(initialScale = 0.92f, animationSpec = tween(300, easing = LinearEasing))) togetherWith
                (slideOutHorizontally(
                    targetOffsetX = { (it * 0.15f).toInt() },
                    animationSpec = tween(300, easing = FastOutSlowInEasing),
                ) + scaleOut(
                    targetScale = 0.92f,
                    animationSpec = tween(300, easing = LinearEasing),
                ))
        },
        predictivePopTransitionSpec = {
            (fadeIn(initialAlpha = 0.5f, animationSpec = tween(300, easing = LinearEasing)) +
                scaleIn(initialScale = 0.92f, animationSpec = tween(300, easing = LinearEasing))) togetherWith
                (slideOutHorizontally(
                    targetOffsetX = { (it * 0.15f).toInt() },
                    animationSpec = tween(300, easing = FastOutSlowInEasing),
                ) + scaleOut(
                    targetScale = 0.92f,
                    animationSpec = tween(300, easing = LinearEasing),
                ))
        },
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            rememberViewModelStoreNavEntryDecorator(),
        ),
        entryProvider = entryProvider {
            authEntries(
                onNavigate = { navigator.navigate(it) },
                onBack = { navigator.goBack() },
                onAuthComplete = {
                    navHostViewModel.onAuthComplete()
                    navigator.navigateToChat()
                },
            )
            chatEntries(
                onNavigate = { navigator.navigate(it) },
                onBack = { navigator.goBack() },
                onNavigateToChat = { navigator.navigateToChat(it) },
                onOpenDrawer = onMenuClick,
                onNavigateToProviderKeys = { endpointName ->
                    navigator.navigateToProviderKeys(endpointName)
                },
                onAttachFromServer = {
                    // Tag the picker with the launching conversation (null on the new-chat
                    // landing) so its selection routes back only to this chat.
                    val launchingId = (navigator.currentRoute as? Chat)?.conversationId
                    navigator.navigate(FilesPicker(targetConversationId = launchingId))
                },
            )
            conversationsEntries(
                onConversationClick = { navigator.navigateToChat(it) },
                onNavigateToArchive = { navigator.navigate(ArchivedConversations) },
                onBack = { navigator.goBack() },
            )
            agentsEntries(
                onNavigate = { navigator.navigate(it) },
                onBack = { navigator.goBack() },
                onStartChat = { agentId ->
                    // Carry the agent id into the new chat so it opens on that agent
                    // (Tier-0 override in ModelSelectionDelegate) rather than falling
                    // back to last-used / first-agent / first-model.
                    navigator.navigateToTopLevel(NewChat(agentId))
                },
            )
            skillsEntries(
                onNavigate = { navigator.navigate(it) },
                onBack = { navigator.goBack() },
            )
            filesEntries(
                onBack = { navigator.goBack() },
            )
            filePickerEntries(
                onConfirm = { targetConversationId, files ->
                    serverFileSelectionHandoff.publish(targetConversationId, files)
                    navigator.goBack()
                },
                onBack = { navigator.goBack() },
            )
            settingsEntries(
                onNavigate = { navigator.navigate(it) },
                onBack = { navigator.goBack() },
                onLogout = {
                    navHostViewModel.logout()
                    navigator.navigateToAuth()
                },
                onNavigateToArchive = { navigator.navigate(ArchivedConversations) },
            )
            memoriesEntry(
                onBack = { navigator.goBack() },
            )
            mcpServersEntry(
                onBack = { navigator.goBack() },
            )
        },
    )
}
