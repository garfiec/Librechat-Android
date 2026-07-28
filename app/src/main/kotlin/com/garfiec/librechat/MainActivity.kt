package com.garfiec.librechat

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import co.touchlab.kermit.Logger
import com.garfiec.librechat.core.common.network.ConnectivityObserver
import com.garfiec.librechat.core.data.datastore.SettingsDataStore
import com.garfiec.librechat.core.data.datastore.ThemeDataStore
import com.garfiec.librechat.core.data.datastore.ThemeMode
import com.garfiec.librechat.core.ui.theme.LibreChatTheme
import com.garfiec.librechat.feature.chat.ShareIntentConsumer
import com.garfiec.librechat.feature.chat.SharedContent
import com.garfiec.librechat.navigation.LibreChatNavHost
import com.garfiec.librechat.navigation.toDeepLinkUri
import com.garfiec.librechat.shared.navigation.DeepLinkResolution
import com.garfiec.librechat.shared.navigation.DeepLinks
import com.garfiec.librechat.shortcuts.ModelShortcuts
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

private const val KEY_PENDING_DEEP_LINK = "pending_deep_link"

class MainActivity : ComponentActivity() {

    private val connectivityObserver: ConnectivityObserver by inject()
    private val themeDataStore: ThemeDataStore by inject()
    private val settingsDataStore: SettingsDataStore by inject()

    private var deepLinkUri by mutableStateOf<Uri?>(null)

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class, ExperimentalComposeUiApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Only process the launch intent on a genuinely fresh start. On recreation (rotation,
        // theme/locale change, restore) it is still sticky; re-processing would re-fire the deep link
        // and yank the user back to it. Instead restore any not-yet-consumed link (see onSaveInstanceState):
        // if the nav host hadn't composed to consume it before a recreation — e.g. a config change during
        // the theme/locale warm-up gate below — it would otherwise be lost, since deepLinkUri isn't saved
        // state and the back stack has nothing to restore yet. A consumed link was already nulled.
        if (savedInstanceState == null) {
            handleIntent(intent)
        } else {
            deepLinkUri = savedInstanceState.getString(KEY_PENDING_DEEP_LINK)?.toUri()
        }

        // Keep home-screen model shortcuts in sync with the account's most-used models. The flow
        // emits an empty list once the account resolves logged-out, which clears the shortcuts.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                settingsDataStore.topUsedModels(ModelShortcuts.maxCount)
                    // The backing flow re-emits on every settings write; only republish when the
                    // ranked list actually changes to avoid redundant main-thread setDynamicShortcuts IPC.
                    .distinctUntilChanged()
                    .collect { models ->
                        ModelShortcuts.publish(this@MainActivity, models)
                    }
            }
        }

        setContent {
            val windowSizeClass = calculateWindowSizeClass(this)
            val isConnected by connectivityObserver.isConnected.collectAsStateWithLifecycle(initialValue = true)
            // Hold off drawing themed content until the persisted theme has resolved, so a
            // dark-mode user on a light-system device never sees a one-frame flash of the wrong
            // theme. The system window background covers the sub-frame gap.
            val themeReady by themeDataStore.isReady.collectAsStateWithLifecycle()
            val themeMode by themeDataStore.themeMode.collectAsStateWithLifecycle(initialValue = themeDataStore.initialThemeMode)
            val accentColorArgb by themeDataStore.accentColor.collectAsStateWithLifecycle(
                initialValue = themeDataStore.initialAccentColor,
            )
            val useDynamicColor by themeDataStore.useDynamicColor.collectAsStateWithLifecycle(
                initialValue = themeDataStore.initialUseDynamicColor,
            )
            // Gate on the language warm-up too so a persisted non-system language is applied
            // before the first frame (no flash of the system locale before switching).
            val localeReady by settingsDataStore.isReady.collectAsStateWithLifecycle()
            val selectedLanguage by settingsDataStore.selectedLanguage.collectAsStateWithLifecycle(
                initialValue = settingsDataStore.initialSelectedLanguage,
            )
            // The DEFAULT_LANGUAGE sentinel ("system") maps to no override → keep the device locale.
            val appLocale = selectedLanguage.takeIf { it != SettingsDataStore.DEFAULT_LANGUAGE }
            val darkTheme = when (themeMode) {
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }

            // Update system bar icon colors to match the app's resolved theme.
            // When light theme: dark icons on light background (isAppearanceLight = true).
            // When dark theme: light icons on dark background (isAppearanceLight = false).
            // This ensures correct visibility even when the user overrides the system theme.
            SideEffect {
                val insetsController = WindowCompat.getInsetsController(window, window.decorView)
                insetsController.isAppearanceLightStatusBars = !darkTheme
                insetsController.isAppearanceLightNavigationBars = !darkTheme
            }

            if (themeReady && localeReady) {
                LibreChatTheme(
                    darkTheme = darkTheme,
                    accentColor = Color(accentColorArgb),
                    useDynamicColor = useDynamicColor,
                ) {
                    Surface(
                        modifier = Modifier
                            .fillMaxSize()
                            .semantics { testTagsAsResourceId = true },
                    ) {
                        Column(modifier = Modifier.fillMaxSize()) {
                            AnimatedVisibility(
                                visible = !isConnected,
                                enter = expandVertically(),
                                exit = shrinkVertically(),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(MaterialTheme.colorScheme.errorContainer)
                                        .windowInsetsPadding(WindowInsets.statusBars)
                                        .padding(vertical = 6.dp, horizontal = 16.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = stringResource(R.string.no_connection),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                    )
                                }
                            }
                            LibreChatNavHost(
                                windowSizeClass = windowSizeClass,
                                deepLinkUri = deepLinkUri,
                                onDeepLinkConsume = { deepLinkUri = null },
                                appLocaleTag = appLocale,
                                modifier = Modifier
                                    .weight(1f)
                                    .then(
                                        if (!isConnected) {
                                            Modifier.consumeWindowInsets(WindowInsets.statusBars)
                                        } else {
                                            Modifier
                                        },
                                    ),
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // Preserve a link that hasn't been placed on the back stack yet, so a recreation during the
        // warm-up gate doesn't drop it (restored in onCreate). A consumed link is already null.
        deepLinkUri?.let { outState.putString(KEY_PENDING_DEEP_LINK, it.toString()) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Keep getIntent() pointing at the latest intent so a later recreation doesn't re-read a stale one.
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return

        when (intent.action) {
            Intent.ACTION_SEND -> handleShareIntent(intent)
            Intent.ACTION_SEND_MULTIPLE -> handleShareMultipleIntent(intent)
            else -> handleDeepLink(intent)
        }
    }

    private fun handleDeepLink(intent: Intent) {
        intent.data?.let { uri ->
            if (uri.scheme != DeepLinks.SCHEME) return@let
            // Same resolver the nav host uses — the accept decision and the routing decision can't
            // drift because they're one source of truth. Anything it doesn't route is dropped here.
            if (DeepLinks.resolve(uri.toDeepLinkUri()) is DeepLinkResolution.None) {
                Logger.w { "Ignoring unhandled deep link: $uri" }
            } else {
                deepLinkUri = uri
            }
        }
    }

    private fun handleShareIntent(intent: Intent) {
        val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)

        @Suppress("DEPRECATION")
        val sharedUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }

        val fileUris = if (sharedUri != null) listOf(sharedUri) else emptyList()

        if (sharedText != null || fileUris.isNotEmpty()) {
            Logger.d { "Share intent received: text=${sharedText != null}, uris=${fileUris.size}" }
            // Staged only — the nav host addresses it to whichever chat is on screen.
            ShareIntentConsumer.setPendingShare(
                SharedContent(text = sharedText, fileUris = fileUris),
            )
        }
    }

    private fun handleShareMultipleIntent(intent: Intent) {
        @Suppress("DEPRECATION")
        val sharedUris = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
        }

        if (!sharedUris.isNullOrEmpty()) {
            Logger.d { "Share multiple intent received: uris=${sharedUris.size}" }
            ShareIntentConsumer.setPendingShare(
                SharedContent(fileUris = sharedUris),
            )
        }
    }
}
