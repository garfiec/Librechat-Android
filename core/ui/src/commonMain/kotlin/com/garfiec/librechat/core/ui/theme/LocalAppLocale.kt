package com.garfiec.librechat.core.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidedValue
import androidx.compose.runtime.key
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection

/**
 * Runtime app-locale override for Compose Multiplatform resources.
 *
 * CMP's `stringResource` resolves against the locale carried in the composition, and there is no
 * public API yet to switch it at runtime. This is the official JetBrains workaround
 * (https://kotlinlang.org/docs/multiplatform/compose-resource-environment.html): an expect/actual
 * object that pushes the chosen locale into the platform + composition, applied through [AppLocale]
 * which wraps the subtree in a `key(...)` so it fully recomposes and re-resolves resources whenever
 * the locale changes.
 */
expect object LocalAppLocale {
    val current: String
        @Composable get

    @Composable
    infix fun provides(value: String?): ProvidedValue<*>
}

/** Language subtags that render right-to-left. We only ship Arabic today; the rest are listed
 *  so a future locale addition mirrors correctly without revisiting this code. */
private val RTL_LANGUAGE_SUBTAGS = setOf("ar", "he", "iw", "fa", "ur")

/**
 * Applies [tag] (a BCP-47 language code such as "es" or "zh") as the app locale for [content].
 * Pass null to fall back to the system/source locale. The `key(tag)` forces a full recomposition
 * of [content] when the locale changes so every `stringResource` re-resolves against the new
 * locale without an app restart.
 *
 * Overriding the locale alone re-resolves strings and right-aligns text but does NOT flip Compose's
 * [LocalLayoutDirection], so RTL languages would otherwise keep LTR-mirrored chrome (back arrow,
 * row icons, chevrons). We therefore provide the layout direction explicitly for an overridden tag;
 * for the system default (null) we leave the platform's current direction untouched.
 *
 * Because `key(tag)` tears down composition state on every locale change, callers must keep
 * navigation state (the nav back stack) ABOVE this wrapper so it survives the swap — see
 * `LibreChatNavHost`, which creates the back stack outside [AppLocale] and only wraps the rendering.
 */
@Composable
fun AppLocale(tag: String?, content: @Composable () -> Unit) {
    val layoutDirection = when {
        tag == null -> LocalLayoutDirection.current
        tag.substringBefore('-').lowercase() in RTL_LANGUAGE_SUBTAGS -> LayoutDirection.Rtl
        else -> LayoutDirection.Ltr
    }
    CompositionLocalProvider(
        LocalAppLocale provides tag,
        LocalLayoutDirection provides layoutDirection,
    ) {
        key(tag) {
            content()
        }
    }
}
