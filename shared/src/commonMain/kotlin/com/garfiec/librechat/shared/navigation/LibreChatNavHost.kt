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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import com.garfiec.librechat.core.common.identity.AccountState
import com.garfiec.librechat.core.logging.Diag
import com.garfiec.librechat.core.ui.components.BannerDisplay
import com.garfiec.librechat.core.ui.theme.AppLocale
import com.garfiec.librechat.feature.agents.navigation.AgentMarketplace
import com.garfiec.librechat.feature.agents.navigation.agentsEntries
import com.garfiec.librechat.feature.auth.navigation.AddAccountServerUrl
import com.garfiec.librechat.feature.auth.navigation.authEntries
import com.garfiec.librechat.feature.auth.navigation.isAddAccountFlowRoute
import com.garfiec.librechat.feature.chat.navigation.Chat
import com.garfiec.librechat.feature.chat.navigation.ModelShortcutBus
import com.garfiec.librechat.feature.chat.navigation.NewChat
import com.garfiec.librechat.feature.chat.navigation.chatEntries
import com.garfiec.librechat.feature.chat.viewmodel.ServerFileSelectionHandoff
import com.garfiec.librechat.feature.conversations.drawer.DrawerViewModel
import com.garfiec.librechat.feature.conversations.navigation.ArchivedConversations
import com.garfiec.librechat.feature.conversations.navigation.ProjectChats
import com.garfiec.librechat.feature.conversations.navigation.Projects
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

/** Sentinel for "no identity recorded yet" in the saved hygiene marker — distinct from null,
 *  which means a recorded logged-out state. */
private const val HYGIENE_UNRECORDED = "__unrecorded__"

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
    hasPendingDeepLink: Boolean = false,
    navHostViewModel: NavHostViewModel = koinViewModel(),
    drawerViewModel: DrawerViewModel = koinViewModel(),
    content: (@Composable (Navigator, NavHostViewModel, Modifier) -> Unit)? = null,
) {
    val isLoggedIn by navHostViewModel.isLoggedIn.collectAsStateWithLifecycle()

    // Stable start key — auth redirect handled via LaunchedEffect below.
    val backStack = rememberNavBackStack(navigationSavedStateConfig, NewChat())
    val navigator = remember(backStack) { Navigator(backStack) }

    // Redirect to auth if not logged in — once per saved-state lifecycle, NOT on every recreation.
    // LaunchedEffect(Unit) restarts on each Activity recreation (config change / process-death restore,
    // see the hygiene note below), but by then the back stack is already restored — possibly to a
    // logged-out-viewable deep-link target (an artifact viewer atop the auth base). Re-firing would
    // clear() that away, so a rememberSaveable latch makes this run only on a genuinely fresh start.
    // Also skipped while a deep link is pending: the deep-link handler owns the initial stack then.
    var initialAuthRedirectDone by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!initialAuthRedirectDone) {
            initialAuthRedirectDone = true
            if (!navHostViewModel.isLoggedIn.value && !hasPendingDeepLink) {
                navigator.navigateToAuth()
            }
        }
    }

    // iOS home-screen quick action: the Swift handler pushes the tapped (endpoint, model) onto the
    // bus; open a NewChat pre-selected on it. Deferred until logged in so a cold launch from a quick
    // action lands the model once auth resolves. Android sets this bus never — it deep-links instead.
    val modelShortcutBus = koinInject<ModelShortcutBus>()
    val pendingModelShortcut by modelShortcutBus.pending.collectAsStateWithLifecycle()
    LaunchedEffect(pendingModelShortcut, isLoggedIn) {
        val ref = pendingModelShortcut ?: return@LaunchedEffect
        if (!isLoggedIn) return@LaunchedEffect
        navigator.navigateToTopLevel(NewChat(endpoint = ref.endpoint, model = ref.model))
        modelShortcutBus.consume()
    }

    // Track active conversation from nav back stack
    LaunchedEffect(navigator.currentRoute) {
        val conversationId = (navigator.currentRoute as? Chat)?.conversationId
        drawerViewModel.setActiveConversation(conversationId)

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

    // A restored back stack can still hold add-account routes after process death, but the pending
    // add session is memory-only and never survives it. Without the session the restored add-mode
    // screens silently fall back to the LIVE server — credentials typed for the new server would be
    // POSTed to the wrong host, and a "success" would replace the active session instead of adding.
    // Strip the flow (and anything stacked above it) before it can render. On a plain Activity
    // recreation the pending session is still alive, so nothing is stripped.
    LaunchedEffect(Unit) {
        if (!navHostViewModel.hasPendingAdd()) {
            val firstAddRoute = backStack.indexOfFirst { it.isAddAccountFlowRoute }
            if (firstAddRoute >= 0) {
                while (backStack.size > firstAddRoute) backStack.removeLastOrNull()
                if (backStack.isEmpty()) backStack.add(NewChat())
            }
        }
    }

    // Cancel an in-progress add-account flow once none of its routes remain on the back stack —
    // covers back-out, the session-expiry reset, and any navigation that abandons the flow. On a
    // successful completion the pending session is already consumed, so the cancel no-ops. Watching
    // the stack (instead of per-screen dispose hooks) survives the flow spanning several routes.
    LaunchedEffect(Unit) {
        snapshotFlow { backStack.any { it.isAddAccountFlowRoute } }
            .collect { inAddFlow ->
                if (!inAddFlow) navHostViewModel.cancelPendingAdd()
            }
    }

    // The active account changed underneath the UI. Drop the process-global Coil cache — it is
    // account-blind (bare-URL keys), so the outgoing account's decoded images must not survive into
    // the next session; the disk half only exists on Android (iOS configures no diskCache). On an
    // account-to-account flip also reset the back stack: the outgoing account's routes point at
    // rows the new account can't read (or, on remove, rows that no longer exist).
    //
    // The identity this UI last ran hygiene for is SAVED STATE, compared against the live resolved
    // one — not a transition-flow collector. A cold collector restarted by Activity recreation or
    // process death initializes its marker to the already-flipped account and silently swallows a
    // flip that landed in the gap; the saved comparison catches up on whatever was missed while
    // this composition did not exist.
    val platformContext = LocalPlatformContext.current
    val accountState by navHostViewModel.accountState.collectAsStateWithLifecycle()
    var hygieneAccountId by rememberSaveable { mutableStateOf<String?>(HYGIENE_UNRECORDED) }
    LaunchedEffect(accountState) {
        val resolved = accountState as? AccountState.Resolved ?: return@LaunchedEffect
        val current = resolved.id?.value
        val previous = hygieneAccountId
        if (previous != HYGIENE_UNRECORDED && previous != current) {
            val imageLoader = SingletonImageLoader.get(platformContext)
            imageLoader.memoryCache?.clear()
            withContext(Dispatchers.IO) { imageLoader.diskCache?.clear() }
            // Reset the stack only on an account-to-account flip; on account-to-logged-out the
            // initiating flow (or the session-expired signal) owns navigation.
            if (previous != null && current != null) {
                navigator.navigateToChat()
            }
        }
        hygieneAccountId = current
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
    val isLoggedIn by navHostViewModel.isLoggedIn.collectAsStateWithLifecycle()
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
        // The drawer is an authenticated surface (conversations, account, settings). Require a session,
        // not just "not in the auth flow" — a logged-out deep link (e.g. an artifact viewer atop the
        // auth base) leaves a non-auth route on top, and without this its edge-swipe would open the
        // drawer over a session-less state.
        gesturesEnabled = isLoggedIn && !navigator.isInAuthFlow,
        drawerContent = {
            ModalDrawerSheet(drawerState = drawerState) {
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
                    // Leave the drawer open (no drawerState.close()) so the user can keep browsing,
                    // unlike onNewChat above.
                    onActiveConversationDelete = {
                        navigator.navigateToTopLevel(NewChat())
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
                    onOpenProjectsIndex = {
                        scope.launch { drawerState.close() }
                        navigator.navigate(Projects)
                    },
                    onSwitchAccount = { accountId ->
                        navHostViewModel.switchAccount(accountId)
                    },
                    onAddAccount = {
                        scope.launch { drawerState.close() }
                        navigator.navigate(AddAccountServerUrl)
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
    drawerViewModel: DrawerViewModel = koinViewModel(),
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
                    // accountTransitions() doesn't fire on login-from-logged-out, so the drawer's
                    // conversation list is refreshed explicitly here (the other half — banners,
                    // version — rides onAuthComplete above). Conversations-only: the login session
                    // tasks already refreshed tags, so this must not re-fetch them (double-fetch).
                    drawerViewModel.refreshConversationsAfterLogin()
                    navigator.navigateToChat()
                },
            )
            chatEntries(
                onNavigate = { navigator.navigate(it) },
                onBack = { navigator.goBack() },
                onNavigateToChat = { conversationId, isTemporary ->
                    navigator.navigateToChat(conversationId, isTemporary)
                },
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
                onNavigateToProject = { projectId, projectName ->
                    navigator.navigate(ProjectChats(projectId, projectName))
                },
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
                // Nav is reactive: signing out promotes the most-recent survivor and stays in the app,
                // while the last-account teardown emits session-expired (handled above) to route to
                // auth. Forcing navigateToAuth() here would wrongly leave the auth screen up after a
                // successor was promoted.
                onLogout = { navHostViewModel.logout() },
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
