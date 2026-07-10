package com.garfiec.librechat.feature.settings.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.garfiec.librechat.core.common.speech.sttEngineSelectsRecognizer
import com.garfiec.librechat.core.common.speech.sttSupportsLiveRecognition
import com.garfiec.librechat.core.model.speech.SttEngine
import com.garfiec.librechat.feature.settings.resources.*
import com.garfiec.librechat.feature.settings.resources.Res
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SttDetailDialog(
    selectedEngine: String,
    selectedLanguage: String,
    selectedOnDevice: Boolean,
    selectedEndOfSpeech: Boolean,
    serverSttEnabled: Boolean,
    availableLanguages: List<String>,
    onConfirm: (engine: String, language: String, onDevice: Boolean, endOfSpeech: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var engine by remember { mutableStateOf(SttEngine.fromStored(selectedEngine)) }
    var language by remember { mutableStateOf(selectedLanguage) }
    var onDevice by remember { mutableStateOf(selectedOnDevice) }
    var endOfSpeech by remember { mutableStateOf(selectedEndOfSpeech) }
    var engineExpanded by remember { mutableStateOf(false) }
    var languageExpanded by remember { mutableStateOf(false) }

    // Browser is always available. External is offered when the server has STT configured OR when
    // it's already the selected engine — so a stored "external" that predates a failed/negative
    // config fetch stays selectable and the dropdown never disagrees with the shown value (rather
    // than showing "External" selected while listing only "Built-in").
    val engineOptions = buildList {
        add(SttEngine.BROWSER)
        if (serverSttEnabled || engine == SttEngine.EXTERNAL) add(SttEngine.EXTERNAL)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.speech_to_text_settings)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Engine selector
                Text(
                    text = stringResource(Res.string.stt_engine_label),
                    style = MaterialTheme.typography.labelMedium,
                )
                ExposedDropdownMenuBox(
                    expanded = engineExpanded,
                    onExpandedChange = { engineExpanded = it },
                ) {
                    OutlinedTextField(
                        value = sttEngineLabel(engine),
                        onValueChange = {},
                        readOnly = true,
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = engineExpanded)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                    )
                    ExposedDropdownMenu(
                        expanded = engineExpanded,
                        onDismissRequest = { engineExpanded = false },
                    ) {
                        engineOptions.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(sttEngineLabel(option)) },
                                onClick = {
                                    engine = option
                                    engineExpanded = false
                                },
                            )
                        }
                    }
                }

                // Engine-specific hint
                Text(
                    text = when (engine) {
                        SttEngine.BROWSER -> stringResource(Res.string.stt_hint_browser)
                        SttEngine.EXTERNAL -> stringResource(Res.string.stt_hint_external)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // On-device + listening-mode toggles: shown only where the live recognizer actually
                // honors them. That needs a platform that runs it (Android <31 uses the one-shot
                // Intent overlay, which reads neither) AND — where the engine choice selects the
                // transport (Android) — the Built-in engine, since External is a single-shot
                // record→upload with no live recognizer. On iOS the engine doesn't select the
                // transport (External falls through to SFSpeechRecognizer), so both prefs apply and
                // the toggles show regardless of the selected engine.
                val liveRecognizerHonorsPrefs = sttSupportsLiveRecognition() &&
                    (engine == SttEngine.BROWSER || !sttEngineSelectsRecognizer())
                if (liveRecognizerHonorsPrefs) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(Res.string.stt_on_device_toggle),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                text = stringResource(Res.string.stt_on_device_toggle_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Switch(
                            checked = onDevice,
                            onCheckedChange = { onDevice = it },
                        )
                    }

                    // End-of-speech: stop automatically when the user pauses (hands-free). With
                    // "Auto-send after STT" on, reaching end-of-speech also sends the message.
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(Res.string.stt_end_of_speech_toggle),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                text = stringResource(Res.string.stt_end_of_speech_toggle_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Switch(
                            checked = endOfSpeech,
                            onCheckedChange = { endOfSpeech = it },
                        )
                    }
                }

                // Language selector
                Text(
                    text = stringResource(Res.string.stt_language_label),
                    style = MaterialTheme.typography.labelMedium,
                )
                ExposedDropdownMenuBox(
                    expanded = languageExpanded,
                    onExpandedChange = { languageExpanded = it },
                ) {
                    OutlinedTextField(
                        value = language.ifBlank { stringResource(Res.string.stt_auto_detect) },
                        onValueChange = {},
                        readOnly = true,
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = languageExpanded)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                    )
                    ExposedDropdownMenu(
                        expanded = languageExpanded,
                        onDismissRequest = { languageExpanded = false },
                    ) {
                        availableLanguages.forEach { item ->
                            DropdownMenuItem(
                                text = { Text(item) },
                                onClick = {
                                    language = item
                                    languageExpanded = false
                                },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(engine.storedValue, language, onDevice, endOfSpeech) }) {
                Text(stringResource(Res.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.action_cancel))
            }
        },
    )
}

/** Display label for an [SttEngine], resolved independently of which options are offered. */
@Composable
private fun sttEngineLabel(engine: SttEngine): String = when (engine) {
    SttEngine.BROWSER -> stringResource(Res.string.stt_engine_browser)
    SttEngine.EXTERNAL -> stringResource(Res.string.stt_engine_external)
}

data class DeviceVoiceInfo(
    val name: String,
    val locale: String,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TtsDetailDialog(
    selectedEngine: String,
    selectedVoice: String,
    speechRate: Float,
    pitch: Float,
    deviceVoiceName: String,
    cachingEnabled: Boolean,
    ttsSource: String,
    availableEngines: List<String>,
    availableVoices: List<String>,
    availableDeviceVoices: List<DeviceVoiceInfo>,
    onConfirm:
    (engine: String, voice: String, rate: Float, pitch: Float, deviceVoiceName: String, caching: Boolean, source: String) -> Unit,
    onDismiss: () -> Unit,
    isPreviewPlaying: Boolean = false,
    onPreviewDevice: (text: String, rate: Float, pitch: Float, voiceName: String?) -> Unit = { _, _, _, _ -> },
    onPreviewServer: (text: String, voice: String?, model: String?) -> Unit = { _, _, _ -> },
    onStopPreview: () -> Unit = {},
) {
    var engine by remember { mutableStateOf(selectedEngine) }
    var voice by remember { mutableStateOf(selectedVoice) }
    var rate by remember { mutableFloatStateOf(speechRate) }
    var currentPitch by remember { mutableFloatStateOf(pitch) }
    var currentDeviceVoice by remember { mutableStateOf(deviceVoiceName) }
    var caching by remember { mutableStateOf(cachingEnabled) }
    var source by remember { mutableStateOf(ttsSource) }
    var engineExpanded by remember { mutableStateOf(false) }
    var voiceExpanded by remember { mutableStateOf(false) }
    var sourceExpanded by remember { mutableStateOf(false) }
    var deviceVoiceExpanded by remember { mutableStateOf(false) }

    val deviceLabel = stringResource(Res.string.tts_source_device)
    val serverLabel = stringResource(Res.string.tts_source_server)
    val sourceOptions = listOf("device" to deviceLabel, "server" to serverLabel)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.text_to_speech_settings)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // TTS Source selector
                Text(
                    text = stringResource(Res.string.tts_source_label),
                    style = MaterialTheme.typography.labelMedium,
                )
                ExposedDropdownMenuBox(
                    expanded = sourceExpanded,
                    onExpandedChange = { sourceExpanded = it },
                ) {
                    OutlinedTextField(
                        value = sourceOptions.firstOrNull { it.first == source }?.second ?: deviceLabel,
                        onValueChange = {},
                        readOnly = true,
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = sourceExpanded)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                    )
                    ExposedDropdownMenu(
                        expanded = sourceExpanded,
                        onDismissRequest = { sourceExpanded = false },
                    ) {
                        sourceOptions.forEach { (value, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    source = value
                                    sourceExpanded = false
                                },
                            )
                        }
                    }
                }

                if (source == "device") {
                    // Device voice picker
                    if (availableDeviceVoices.isNotEmpty()) {
                        Text(
                            text = stringResource(Res.string.tts_voice_label),
                            style = MaterialTheme.typography.labelMedium,
                        )
                        val selectedDisplayName = availableDeviceVoices
                            .find { it.name == currentDeviceVoice }
                            ?.let { "${it.name} (${it.locale})" }
                            ?: stringResource(Res.string.tts_system_default)
                        ExposedDropdownMenuBox(
                            expanded = deviceVoiceExpanded,
                            onExpandedChange = { deviceVoiceExpanded = it },
                        ) {
                            OutlinedTextField(
                                value = selectedDisplayName,
                                onValueChange = {},
                                readOnly = true,
                                trailingIcon = {
                                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = deviceVoiceExpanded)
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                            )
                            ExposedDropdownMenu(
                                expanded = deviceVoiceExpanded,
                                onDismissRequest = { deviceVoiceExpanded = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(Res.string.tts_system_default)) },
                                    onClick = {
                                        currentDeviceVoice = ""
                                        deviceVoiceExpanded = false
                                    },
                                )
                                availableDeviceVoices.forEach { dv ->
                                    DropdownMenuItem(
                                        text = {
                                            Column {
                                                Text(
                                                    text = dv.name,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                )
                                                Text(
                                                    text = dv.locale,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        },
                                        onClick = {
                                            currentDeviceVoice = dv.name
                                            deviceVoiceExpanded = false
                                        },
                                    )
                                }
                            }
                        }
                    }

                    // Speech rate slider
                    Text(
                        text = stringResource(Res.string.tts_speech_rate, rate),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Slider(
                        value = rate,
                        onValueChange = { rate = it },
                        valueRange = 0.5f..2.0f,
                        steps = 5,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    // Pitch slider
                    Text(
                        text = stringResource(Res.string.tts_pitch, currentPitch),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Slider(
                        value = currentPitch,
                        onValueChange = { currentPitch = it },
                        valueRange = 0.5f..2.0f,
                        steps = 5,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                if (source == "server") {
                    // Engine selector (only for server TTS)
                    Text(
                        text = stringResource(Res.string.stt_engine_label),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    ExposedDropdownMenuBox(
                        expanded = engineExpanded,
                        onExpandedChange = { engineExpanded = it },
                    ) {
                        OutlinedTextField(
                            value = engine.ifBlank { stringResource(Res.string.stt_default) },
                            onValueChange = {},
                            readOnly = true,
                            trailingIcon = {
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = engineExpanded)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                        )
                        ExposedDropdownMenu(
                            expanded = engineExpanded,
                            onDismissRequest = { engineExpanded = false },
                        ) {
                            availableEngines.forEach { item ->
                                DropdownMenuItem(
                                    text = { Text(item) },
                                    onClick = {
                                        engine = item
                                        engineExpanded = false
                                    },
                                )
                            }
                        }
                    }

                    // Voice selector (only for server TTS)
                    Text(
                        text = stringResource(Res.string.tts_voice_label),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    ExposedDropdownMenuBox(
                        expanded = voiceExpanded,
                        onExpandedChange = { voiceExpanded = it },
                    ) {
                        OutlinedTextField(
                            value = voice.ifBlank { stringResource(Res.string.stt_default) },
                            onValueChange = {},
                            readOnly = true,
                            trailingIcon = {
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = voiceExpanded)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                        )
                        ExposedDropdownMenu(
                            expanded = voiceExpanded,
                            onDismissRequest = { voiceExpanded = false },
                        ) {
                            availableVoices.forEach { item ->
                                DropdownMenuItem(
                                    text = { Text(item) },
                                    onClick = {
                                        voice = item
                                        voiceExpanded = false
                                    },
                                )
                            }
                        }
                    }

                    // Caching toggle (only for server TTS)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(Res.string.tts_cache_audio),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Switch(
                            checked = caching,
                            onCheckedChange = { caching = it },
                        )
                    }
                }

                // Preview button
                val previewText = stringResource(Res.string.tts_preview_text)
                val previewCd =
                    stringResource(if (isPreviewPlaying) Res.string.cd_stop_voice_preview else Res.string.cd_preview_voice)
                FilledTonalButton(
                    onClick = {
                        if (isPreviewPlaying) {
                            onStopPreview()
                        } else {
                            if (source == "device") {
                                onPreviewDevice(
                                    previewText,
                                    rate,
                                    currentPitch,
                                    currentDeviceVoice.ifBlank { null },
                                )
                            } else {
                                onPreviewServer(
                                    previewText,
                                    voice.ifBlank { null },
                                    engine.ifBlank { null },
                                )
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics {
                            contentDescription = previewCd
                        },
                ) {
                    Icon(
                        imageVector = if (isPreviewPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(if (isPreviewPlaying) Res.string.tts_stop_preview else Res.string.tts_preview_voice))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onStopPreview()
                onConfirm(engine, voice, rate, currentPitch, currentDeviceVoice, caching, source)
            }) {
                Text(stringResource(Res.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = {
                onStopPreview()
                onDismiss()
            }) {
                Text(stringResource(Res.string.action_cancel))
            }
        },
    )
}
