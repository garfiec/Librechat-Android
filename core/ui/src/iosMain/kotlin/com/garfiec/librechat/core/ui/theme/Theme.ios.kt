package com.garfiec.librechat.core.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable

@Composable
actual fun platformColorScheme(darkTheme: Boolean, dynamicColor: Boolean): ColorScheme? = null

actual fun supportsDynamicColor(): Boolean = false
