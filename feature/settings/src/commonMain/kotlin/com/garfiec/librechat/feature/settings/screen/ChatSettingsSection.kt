package com.garfiec.librechat.feature.settings.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.garfiec.librechat.core.common.ChatLayoutConstants
import com.garfiec.librechat.core.data.datastore.ChatFontSize
import com.garfiec.librechat.core.data.datastore.ChatHeaderAlignment
import com.garfiec.librechat.core.data.datastore.ChatHeaderContent
import com.garfiec.librechat.core.data.datastore.LatexRenderer
import com.garfiec.librechat.core.data.datastore.StarredModelsDisplay
import com.garfiec.librechat.feature.settings.resources.*
import com.garfiec.librechat.feature.settings.resources.Res
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun ChatSettingsSection(
    fontSize: ChatFontSize,
    autoScrollEnabled: Boolean,
    showThinkingBlocks: Boolean,
    showImageDescriptions: Boolean,
    dismissKeyboardOnSend: Boolean,
    chatLayoutStyle: String,
    showAvatars: Boolean,
    showBubbles: Boolean,
    latexRenderer: LatexRenderer,
    starredModelsDisplay: StarredModelsDisplay,
    chatHeaderContent: ChatHeaderContent,
    chatHeaderAlignment: ChatHeaderAlignment,
    onFontSizeChange: (ChatFontSize) -> Unit,
    onAutoScrollChange: (Boolean) -> Unit,
    onShowThinkingChange: (Boolean) -> Unit,
    onShowImageDescriptionsChange: (Boolean) -> Unit,
    onDismissKeyboardOnSendChange: (Boolean) -> Unit,
    onChatLayoutStyleChange: (String) -> Unit,
    onShowAvatarsChange: (Boolean) -> Unit,
    onShowBubblesChange: (Boolean) -> Unit,
    onLatexRendererChange: (LatexRenderer) -> Unit,
    onStarredModelsDisplayChange: (StarredModelsDisplay) -> Unit,
    onChatHeaderContentChange: (ChatHeaderContent) -> Unit,
    onChatHeaderAlignmentChange: (ChatHeaderAlignment) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            // Chat layout style selector
            Text(
                text = stringResource(Res.string.chat_layout),
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(modifier = Modifier.height(4.dp))
            listOf(
                ChatLayoutConstants.THREAD to stringResource(Res.string.chat_layout_thread),
                ChatLayoutConstants.TWO_SIDED to stringResource(Res.string.chat_layout_two_sided),
            ).forEach { (value, label) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .selectable(
                            selected = chatLayoutStyle == value,
                            onClick = { onChatLayoutStyleChange(value) },
                            role = Role.RadioButton,
                        )
                        .padding(vertical = 8.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = chatLayoutStyle == value,
                        onClick = null,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = if (value == ChatLayoutConstants.THREAD) {
                                stringResource(Res.string.chat_layout_thread_desc)
                            } else {
                                stringResource(Res.string.chat_layout_two_sided_desc)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Show bubbles toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(Res.string.show_bubbles),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = stringResource(Res.string.show_bubbles_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                Switch(
                    checked = showBubbles,
                    onCheckedChange = onShowBubblesChange,
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Show avatars toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(Res.string.show_avatars),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = stringResource(Res.string.show_avatars_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                Switch(
                    checked = showAvatars,
                    onCheckedChange = onShowAvatarsChange,
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Font size selector
            Text(
                text = stringResource(Res.string.font_size),
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(modifier = Modifier.height(4.dp))
            ChatFontSize.entries.forEach { size ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .selectable(
                            selected = fontSize == size,
                            onClick = { onFontSizeChange(size) },
                            role = Role.RadioButton,
                        )
                        .padding(vertical = 8.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = fontSize == size,
                        onClick = null,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = when (size) {
                            ChatFontSize.SMALL -> stringResource(Res.string.font_size_small)
                            ChatFontSize.MEDIUM -> stringResource(Res.string.font_size_medium)
                            ChatFontSize.LARGE -> stringResource(Res.string.font_size_large)
                        },
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // LaTeX renderer selector
            Text(
                text = stringResource(Res.string.latex_renderer),
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(modifier = Modifier.height(4.dp))
            listOf(
                LatexRenderer.KATEX to (stringResource(Res.string.latex_katex) to stringResource(Res.string.latex_katex_desc)),
                LatexRenderer.NATIVE to (stringResource(Res.string.latex_native) to stringResource(Res.string.latex_native_desc)),
            ).forEach { (renderer, labelAndDesc) ->
                val (label, description) = labelAndDesc
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .selectable(
                            selected = latexRenderer == renderer,
                            onClick = { onLatexRendererChange(renderer) },
                            role = Role.RadioButton,
                        )
                        .padding(vertical = 8.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = latexRenderer == renderer,
                        onClick = null,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Auto-scroll toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(Res.string.auto_scroll),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = stringResource(Res.string.auto_scroll_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                Switch(
                    checked = autoScrollEnabled,
                    onCheckedChange = onAutoScrollChange,
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Show thinking blocks toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(Res.string.show_thinking_blocks),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = stringResource(Res.string.show_thinking_blocks_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                Switch(
                    checked = showThinkingBlocks,
                    onCheckedChange = onShowThinkingChange,
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Show image descriptions toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(Res.string.show_image_descriptions),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = stringResource(Res.string.show_image_descriptions_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                Switch(
                    checked = showImageDescriptions,
                    onCheckedChange = onShowImageDescriptionsChange,
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Dismiss keyboard on send toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(Res.string.dismiss_keyboard_on_send),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = stringResource(Res.string.dismiss_keyboard_on_send_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                Switch(
                    checked = dismissKeyboardOnSend,
                    onCheckedChange = onDismissKeyboardOnSendChange,
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Starred models display selector (mobile-only model-picker behavior)
            SettingsRadioGroup(
                title = stringResource(Res.string.starred_models_title),
                description = stringResource(Res.string.starred_models_desc),
                options = StarredModelsDisplay.entries,
                selected = starredModelsDisplay,
                onSelect = onStarredModelsDisplayChange,
            ) { option ->
                when (option) {
                    StarredModelsDisplay.OFF -> stringResource(Res.string.starred_models_off)
                    StarredModelsDisplay.GROUPED -> stringResource(Res.string.starred_models_grouped)
                    StarredModelsDisplay.TOP -> stringResource(Res.string.starred_models_top)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Chat header content selector (mobile-only floating-top-bar behavior)
            SettingsRadioGroup(
                title = stringResource(Res.string.chat_header_content_title),
                description = stringResource(Res.string.chat_header_content_desc),
                options = ChatHeaderContent.entries,
                selected = chatHeaderContent,
                onSelect = onChatHeaderContentChange,
            ) { option ->
                when (option) {
                    ChatHeaderContent.TITLE -> stringResource(Res.string.chat_header_content_title_option)
                    ChatHeaderContent.MODEL -> stringResource(Res.string.chat_header_content_model)
                    ChatHeaderContent.NONE -> stringResource(Res.string.chat_header_content_none)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Chat header bubble alignment selector (mobile-only floating-top-bar behavior)
            SettingsRadioGroup(
                title = stringResource(Res.string.chat_header_alignment_title),
                description = stringResource(Res.string.chat_header_alignment_desc),
                options = ChatHeaderAlignment.entries,
                selected = chatHeaderAlignment,
                onSelect = onChatHeaderAlignmentChange,
            ) { option ->
                when (option) {
                    ChatHeaderAlignment.LEFT -> stringResource(Res.string.chat_header_alignment_left)
                    ChatHeaderAlignment.CENTER -> stringResource(Res.string.chat_header_alignment_center)
                    ChatHeaderAlignment.FILL -> stringResource(Res.string.chat_header_alignment_fill)
                }
            }
        }
        HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
    }
}

private val RadioRowShape = RoundedCornerShape(8.dp)

/**
 * A titled single-select radio group over an enum's [options]. Shared by the chat-settings
 * selectors (starred-models display, chat-header content, chat-header alignment) so the row
 * styling and selection a11y stay in one place. [optionLabel] resolves each option's display
 * string (a `@Composable` so callers can use `stringResource`).
 */
@Composable
private fun <T> SettingsRadioGroup(
    title: String,
    description: String,
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    optionLabel: @Composable (T) -> String,
) {
    Column(modifier = modifier) {
        Text(text = title, style = MaterialTheme.typography.bodyLarge)
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))
        options.forEach { option ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RadioRowShape)
                    .selectable(
                        selected = selected == option,
                        onClick = { onSelect(option) },
                        role = Role.RadioButton,
                    )
                    .padding(vertical = 8.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = selected == option, onClick = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = optionLabel(option), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}
