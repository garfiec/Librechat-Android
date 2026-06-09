package com.garfiec.librechat.feature.settings.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.garfiec.librechat.core.data.datastore.SettingsDataStore
import com.garfiec.librechat.feature.settings.resources.*
import com.garfiec.librechat.feature.settings.resources.Res
import org.jetbrains.compose.resources.stringResource

data class LanguageOption(
    val code: String,
    val displayName: String,
)

/**
 * The languages the app actually ships translations for. Shown as endonyms (native names), which
 * by convention are not themselves translated. The "System default" sentinel
 * ([SettingsDataStore.DEFAULT_LANGUAGE]) is prepended at render time since its label is localized.
 */
private val SUPPORTED_LANGUAGES = listOf(
    LanguageOption("en", "English"),
    LanguageOption("es", "Español"),
    LanguageOption("fr", "Français"),
    LanguageOption("de", "Deutsch"),
    LanguageOption("pt", "Português"),
    LanguageOption("ru", "Русский"),
    LanguageOption("zh", "中文（简体）"),
    LanguageOption("ja", "日本語"),
    LanguageOption("ko", "한국어"),
    LanguageOption("ar", "العربية"),
)

/** Display name (endonym) for a stored language [code], or [systemLabel] for the system sentinel. */
internal fun languageDisplayName(code: String, systemLabel: String): String =
    if (code == SettingsDataStore.DEFAULT_LANGUAGE) {
        systemLabel
    } else {
        SUPPORTED_LANGUAGES.firstOrNull { it.code == code }?.displayName ?: code
    }

/** Searchable single-select language picker over the shipped locales. */
@Composable
internal fun LanguageSelectorDialog(
    selectedLanguage: String,
    onLanguageSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var searchQuery by remember { mutableStateOf("") }

    val systemOption = LanguageOption(
        SettingsDataStore.DEFAULT_LANGUAGE,
        stringResource(Res.string.language_system_default),
    )
    val languages = remember(systemOption) { listOf(systemOption) + SUPPORTED_LANGUAGES }

    val filtered = remember(searchQuery, languages) {
        if (searchQuery.isBlank()) {
            languages
        } else {
            languages.filter {
                it.displayName.contains(searchQuery, ignoreCase = true) ||
                    it.code.contains(searchQuery, ignoreCase = true)
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = { Text(stringResource(Res.string.language)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text(stringResource(Res.string.hint_search_languages)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp),
                ) {
                    items(filtered, key = { it.code }, contentType = { "language" }) { language ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onLanguageSelect(language.code) }
                                .padding(vertical = 12.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = language.displayName,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f),
                            )
                            if (language.code == selectedLanguage) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = stringResource(Res.string.cd_selected),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.action_done))
            }
        },
    )
}
