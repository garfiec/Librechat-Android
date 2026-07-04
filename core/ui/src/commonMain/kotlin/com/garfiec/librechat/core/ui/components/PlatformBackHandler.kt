package com.garfiec.librechat.core.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.backhandler.BackHandler

/**
 * KMP wrapper over Compose UI's [BackHandler]. When [enabled], intercepts the platform back gesture
 * and invokes [onBack]. On platforms without a system back (iOS) this never fires, so callers must
 * also offer an explicit back affordance. Lives in :core:ui because the `androidx.compose.ui.backhandler`
 * package is only on that module's classpath, not exposed to downstream feature/shared modules.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    BackHandler(enabled = enabled, onBack = onBack)
}
