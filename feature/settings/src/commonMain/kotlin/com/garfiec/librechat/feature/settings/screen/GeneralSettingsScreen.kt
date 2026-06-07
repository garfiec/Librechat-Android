package com.garfiec.librechat.feature.settings.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Tablet
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.garfiec.librechat.core.data.datastore.ThemeMode
import com.garfiec.librechat.core.model.config.BuildInfo
import com.garfiec.librechat.core.ui.components.toHexString
import com.garfiec.librechat.feature.settings.resources.*
import com.garfiec.librechat.feature.settings.resources.Res
import com.garfiec.librechat.feature.settings.viewmodel.SettingsViewModel
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/** Dim factor applied to the accent row when wallpaper colors override the custom seed. */
private const val DISABLED_ALPHA = 0.4f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GeneralSettingsScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.title_general)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(Res.string.cd_back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        GeneralSettingsContent(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        )
    }
}

/**
 * Reusable General settings content (without Scaffold/TopAppBar).
 * Used by both the standalone screen and the tabbed settings screen.
 */
@Composable
fun GeneralSettingsContent(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Column(modifier = modifier) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
        ) {
            // Appearance section
            item(key = "appearance_header") {
                SectionHeader(stringResource(Res.string.section_appearance))
            }
            item(key = "theme_selector") {
                ThemeSelector(
                    selected = uiState.themeMode,
                    onSelect = viewModel::setThemeMode,
                )
            }
            item(key = "accent_color_row") {
                AccentColorRow(
                    color = uiState.accentColor,
                    enabled = !uiState.useDynamicColor,
                    onClick = viewModel::showAccentColorDialog,
                )
            }
            if (uiState.dynamicColorSupported) {
                item(key = "dynamic_color_toggle") {
                    DynamicColorToggle(
                        enabled = uiState.useDynamicColor,
                        onEnabledChange = viewModel::setUseDynamicColor,
                    )
                }
            }

            // Language
            item(key = "general_header") {
                SectionHeader(stringResource(Res.string.section_language))
            }
            item(key = "language_row") {
                GeneralSettingsRow(
                    icon = Icons.Default.Language,
                    title = stringResource(Res.string.language),
                    subtitle = uiState.selectedLanguage.uppercase(),
                    onClick = viewModel::showLanguageDialog,
                )
            }

            // Tablet section
            item(key = "tablet_header") {
                SectionHeader(stringResource(Res.string.section_layout))
            }
            item(key = "tablet_sidebar_gesture") {
                TabletSidebarGestureToggle(
                    gestureEnabled = uiState.tabletSidebarGestureEnabled,
                    onGestureEnabledChange = viewModel::setTabletSidebarGestureEnabled,
                )
            }

            // Personalization
            item(key = "personalization_header") {
                SectionHeader(stringResource(Res.string.section_personalization))
            }
            item(key = "personalization_row") {
                GeneralSettingsRow(
                    icon = Icons.Default.Person,
                    title = stringResource(Res.string.personalization),
                    subtitle = if (uiState.personalizationEnabled) {
                        stringResource(Res.string.status_enabled)
                    } else {
                        stringResource(Res.string.status_disabled)
                    },
                    onClick = viewModel::showPersonalizationDialog,
                )
            }

            // About section
            item(key = "about_header") {
                SectionHeader(stringResource(Res.string.section_about))
            }
            item(key = "about_info") {
                AboutInfo(
                    serverUrl = uiState.serverUrl,
                    appVersion = uiState.appVersion,
                    gitSha = uiState.gitSha,
                    buildInfo = uiState.buildInfo,
                )
            }

            // Bottom spacing
            item { Spacer(modifier = Modifier.height(32.dp)) }
        }

        // Language selector dialog
        if (uiState.showLanguageDialog) {
            LanguageSelectorDialog(
                selectedLanguage = uiState.selectedLanguage,
                onLanguageSelect = viewModel::setLanguage,
                onDismiss = viewModel::dismissLanguageDialog,
            )
        }

        // Personalization dialog
        if (uiState.showPersonalizationDialog) {
            PersonalizationDialog(
                aboutUser = uiState.aboutUser,
                responseStyle = uiState.responseStyle,
                enabled = uiState.personalizationEnabled,
                onSave = viewModel::savePersonalization,
                onDismiss = viewModel::dismissPersonalizationDialog,
            )
        }

        if (uiState.showAccentColorDialog) {
            AccentColorDialog(
                currentColor = uiState.accentColor,
                onColorSelect = {
                    viewModel.setAccentColor(it)
                    viewModel.dismissAccentColorDialog()
                },
                onDismiss = viewModel::dismissAccentColorDialog,
            )
        }
    } // Column
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .semantics { heading() },
    )
}

@Composable
private fun ThemeSelector(
    selected: ThemeMode,
    onSelect: (ThemeMode) -> Unit,
) {
    Column {
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            ThemeMode.entries.forEach { mode ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .selectable(
                            selected = selected == mode,
                            onClick = { onSelect(mode) },
                            role = Role.RadioButton,
                        )
                        .padding(vertical = 8.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = selected == mode,
                        onClick = null,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = when (mode) {
                            ThemeMode.SYSTEM -> stringResource(Res.string.theme_system)
                            ThemeMode.LIGHT -> stringResource(Res.string.theme_light)
                            ThemeMode.DARK -> stringResource(Res.string.theme_dark)
                        },
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }
        HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun TabletSidebarGestureToggle(
    gestureEnabled: Boolean,
    onGestureEnabledChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.Tablet,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(Res.string.sidebar_swipe_gesture),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = stringResource(Res.string.sidebar_swipe_gesture_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                Switch(
                    checked = gestureEnabled,
                    onCheckedChange = onGestureEnabledChange,
                )
            }
        }
        HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun AccentColorRow(
    color: Int,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            onClick = onClick,
            enabled = enabled,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .alpha(if (enabled) 1f else DISABLED_ALPHA)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.Palette,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(Res.string.accent_color),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = Color(color).toHexString(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(Color(color))
                        .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
                )
            }
        }
        HorizontalDivider()
    }
}

@Composable
private fun DynamicColorToggle(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Wallpaper,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(Res.string.use_wallpaper_colors),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = stringResource(Res.string.use_wallpaper_colors_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Switch(
                checked = enabled,
                onCheckedChange = onEnabledChange,
            )
        }
        HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun AboutInfo(
    serverUrl: String,
    appVersion: String,
    gitSha: String,
    buildInfo: BuildInfo?,
) {
    Column {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(Res.string.app_version_label),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = appVersion,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (gitSha.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = stringResource(Res.string.app_build_label),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = gitSha,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(Res.string.server_label),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = serverUrl.ifBlank { stringResource(Res.string.server_not_configured) },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            // Server build metadata (v0.8.6) — only when the server reports it.
            val commit = buildInfo?.commitShort ?: buildInfo?.commit
            if (!commit.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                AboutRow(stringResource(Res.string.server_build_commit_label), commit)
            }
            buildInfo?.branch?.takeIf { it.isNotBlank() }?.let { branch ->
                Spacer(modifier = Modifier.height(8.dp))
                AboutRow(stringResource(Res.string.server_build_branch_label), branch)
            }
            buildInfo?.buildDate?.takeIf { it.isNotBlank() }?.let { buildDate ->
                Spacer(modifier = Modifier.height(8.dp))
                AboutRow(stringResource(Res.string.server_build_date_label), buildDate)
            }
        }
        HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun AboutRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}

@Composable
private fun GeneralSettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            onClick = onClick,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HorizontalDivider()
    }
}
