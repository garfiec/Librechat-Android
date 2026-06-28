package com.garfiec.librechat.feature.settings.screen

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.garfiec.librechat.feature.settings.resources.*
import com.garfiec.librechat.feature.settings.resources.Res
import com.garfiec.librechat.feature.settings.viewmodel.SettingsViewModel
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatSettingsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToPresets: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.title_chat)) },
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
        ChatSettingsContent(
            onNavigateToPresets = onNavigateToPresets,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        )
    }
}

/**
 * Reusable Chat settings content (without Scaffold/TopAppBar).
 * Used by both the standalone screen and the tabbed settings screen.
 */
@Composable
fun ChatSettingsContent(
    onNavigateToPresets: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Box(modifier = modifier) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
        ) {
            // Chat Preferences section
            item(key = "chat_header") {
                SectionHeader(stringResource(Res.string.section_chat_preferences))
            }
            item(key = "chat_settings") {
                ChatSettingsSection(
                    fontSize = uiState.chatFontSize,
                    autoScrollEnabled = uiState.autoScrollEnabled,
                    showThinkingBlocks = uiState.showThinkingBlocks,
                    showImageDescriptions = uiState.showImageDescriptions,
                    dismissKeyboardOnSend = uiState.dismissKeyboardOnSend,
                    chatLayoutStyle = uiState.chatLayoutStyle,
                    showAvatars = uiState.showAvatars,
                    showBubbles = uiState.showBubbles,
                    latexRenderer = uiState.latexRenderer,
                    starredModelsDisplay = uiState.starredModelsDisplay,
                    chatHeaderContent = uiState.chatHeaderContent,
                    chatHeaderAlignment = uiState.chatHeaderAlignment,
                    onFontSizeChange = viewModel::setChatFontSize,
                    onAutoScrollChange = viewModel::setAutoScrollEnabled,
                    onShowThinkingChange = viewModel::setShowThinkingBlocks,
                    onShowImageDescriptionsChange = viewModel::setShowImageDescriptions,
                    onDismissKeyboardOnSendChange = viewModel::setDismissKeyboardOnSend,
                    onChatLayoutStyleChange = viewModel::setChatLayoutStyle,
                    onShowAvatarsChange = viewModel::setShowAvatars,
                    onShowBubblesChange = viewModel::setShowBubbles,
                    onLatexRendererChange = viewModel::setLatexRenderer,
                    onStarredModelsDisplayChange = viewModel::setStarredModelsDisplay,
                    onChatHeaderContentChange = viewModel::setChatHeaderContent,
                    onChatHeaderAlignmentChange = viewModel::setChatHeaderAlignment,
                )
            }

            // Artifacts section
            item(key = "artifacts_header") {
                SectionHeader(stringResource(Res.string.section_artifacts))
            }
            item(key = "artifacts_settings") {
                ArtifactSettingsSection(
                    prefs = uiState.inlineArtifactPrefs,
                    displayPrefs = uiState.artifactDisplayPrefs,
                    onDisplayModeChange = viewModel::setArtifactDisplayMode,
                    onMermaidChange = viewModel::setInlineArtifactMermaid,
                    onSvgChange = viewModel::setInlineArtifactSvg,
                    onHtmlChange = viewModel::setInlineArtifactHtml,
                    onReactChange = viewModel::setInlineArtifactReact,
                    onMarkdownChange = viewModel::setInlineArtifactMarkdown,
                )
            }

            // Presets section
            item(key = "presets_header") {
                SectionHeader(stringResource(Res.string.section_presets))
            }
            item(key = "presets_row") {
                ChatSettingsRow(
                    icon = Icons.Default.Brush,
                    title = stringResource(Res.string.presets),
                    subtitle = stringResource(Res.string.presets_subtitle),
                    onClick = onNavigateToPresets,
                )
            }

            // Advanced section
            item(key = "advanced_header") {
                SectionHeader(stringResource(Res.string.section_advanced))
            }
            item(key = "fork_settings_row") {
                ChatSettingsRow(
                    icon = Icons.AutoMirrored.Filled.Chat,
                    title = stringResource(Res.string.fork_behavior),
                    subtitle = ForkMode.fromApiValue(uiState.forkMode).label,
                    onClick = viewModel::showForkSettingsDialog,
                )
            }
            item(key = "commands_row") {
                ChatSettingsRow(
                    icon = Icons.Default.Terminal,
                    title = stringResource(Res.string.commands),
                    subtitle = stringResource(Res.string.commands_enabled_count, uiState.commands.count { it.enabled }),
                    onClick = viewModel::showCommandsScreen,
                )
            }

            // Speech section
            item(key = "speech_header") {
                SectionHeader(stringResource(Res.string.section_speech))
            }
            item(key = "speech_settings") {
                SpeechSettingsSection(
                    autoSendAfterSttEnabled = uiState.sttAutoSend,
                    autoReadEnabled = uiState.autoReadEnabled,
                    selectedVoice = uiState.selectedVoice,
                    availableVoices = uiState.availableVoices,
                    ttsSource = uiState.ttsSource,
                    onAutoSendAfterSttChange = viewModel::setAutoSendAfterStt,
                    onAutoReadChange = viewModel::setAutoReadEnabled,
                    onVoiceSelect = viewModel::selectVoice,
                    onTestVoice = viewModel::testVoice,
                )
            }
            item(key = "speech_detail_buttons") {
                SpeechDetailButtons(
                    onSttDetailClick = viewModel::showSttDetailDialog,
                    onTtsDetailClick = viewModel::showTtsDetailDialog,
                )
            }

            // Bottom spacing
            item { Spacer(modifier = Modifier.height(32.dp)) }
        }

        // Fork settings dialog
        if (uiState.showForkSettingsDialog) {
            ForkSettingsDialog(
                selectedMode = ForkMode.fromApiValue(uiState.forkMode),
                onModeSelect = { mode ->
                    viewModel.setForkMode(mode.apiValue)
                },
                onDismiss = viewModel::dismissForkSettingsDialog,
            )
        }

        // STT detail dialog
        if (uiState.showSttDetailDialog) {
            SttDetailDialog(
                selectedEngine = uiState.sttEngine,
                selectedLanguage = uiState.sttLanguage,
                availableEngines = listOf("Default", "Whisper", "Google"),
                availableLanguages = listOf("Auto-detect", "English", "Spanish", "French", "German", "Japanese", "Chinese"),
                onConfirm = viewModel::saveSttSettings,
                onDismiss = viewModel::dismissSttDetailDialog,
            )
        }

        // TTS detail dialog
        if (uiState.showTtsDetailDialog) {
            TtsDetailDialog(
                selectedEngine = uiState.ttsEngine,
                selectedVoice = uiState.ttsVoice,
                speechRate = uiState.ttsSpeechRate,
                pitch = uiState.ttsPitch,
                deviceVoiceName = uiState.ttsDeviceVoiceName,
                cachingEnabled = uiState.ttsCaching,
                ttsSource = uiState.ttsSource,
                availableEngines = listOf("Default", "ElevenLabs", "OpenAI"),
                availableVoices = uiState.availableVoices.map { it.name }.ifEmpty {
                    listOf("Default", "Alloy", "Echo", "Fable", "Onyx", "Nova", "Shimmer")
                },
                availableDeviceVoices = uiState.availableDeviceVoices,
                isPreviewPlaying = uiState.isTtsPreviewPlaying,
                onPreviewDevice = viewModel::previewDeviceTts,
                onPreviewServer = viewModel::previewServerTts,
                onStopPreview = viewModel::stopTtsPreview,
                onConfirm = viewModel::saveTtsSettings,
                onDismiss = viewModel::dismissTtsDetailDialog,
            )
        }

        // Commands screen (full screen overlay)
        if (uiState.showCommandsScreen) {
            CommandsConfigScreen(
                commands = uiState.commands.map { cmd ->
                    CommandConfig(
                        name = cmd.name,
                        description = cmd.description,
                        enabled = cmd.enabled,
                    )
                },
                onToggleCommand = viewModel::toggleCommand,
                onNavigateBack = viewModel::hideCommandsScreen,
            )
        }
    } // Box
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
private fun SpeechDetailButtons(
    onSttDetailClick: () -> Unit,
    onTtsDetailClick: () -> Unit,
) {
    Column {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = onSttDetailClick,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(Res.string.speech_to_text_settings))
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            OutlinedButton(
                onClick = onTtsDetailClick,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(Res.string.text_to_speech_settings))
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
        HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun ChatSettingsRow(
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
