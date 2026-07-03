package com.garfiec.librechat.feature.settings.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.garfiec.librechat.core.data.datastore.ChatFontSize
import com.garfiec.librechat.core.data.datastore.ChatHeaderAlignment
import com.garfiec.librechat.core.data.datastore.ChatHeaderContent
import com.garfiec.librechat.core.data.datastore.ContextBarPlacement
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
    contextBarPlacement: ContextBarPlacement,
    showImageDescriptions: Boolean,
    dismissKeyboardOnSend: Boolean,
    chatLayoutStyle: String,
    showAvatars: Boolean,
    showBubbles: Boolean,
    latexRenderer: LatexRenderer,
    starredModelsDisplay: StarredModelsDisplay,
    chatHeaderContent: ChatHeaderContent,
    chatHeaderAlignment: ChatHeaderAlignment,
    onAutoScrollChange: (Boolean) -> Unit,
    onShowThinkingChange: (Boolean) -> Unit,
    onShowImageDescriptionsChange: (Boolean) -> Unit,
    onDismissKeyboardOnSendChange: (Boolean) -> Unit,
    onShowAvatarsChange: (Boolean) -> Unit,
    onShowBubblesChange: (Boolean) -> Unit,
    onOpenDialog: (ChatSettingDialog) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                GroupLabel(stringResource(Res.string.chat_group_display))

                SelectorRow(
                    title = stringResource(Res.string.chat_layout),
                    value = chatLayoutLabel(chatLayoutStyle),
                    onClick = { onOpenDialog(ChatSettingDialog.CHAT_LAYOUT) },
                )

                ToggleRow(
                    title = stringResource(Res.string.show_bubbles),
                    description = stringResource(Res.string.show_bubbles_desc),
                    checked = showBubbles,
                    onChange = onShowBubblesChange,
                )

                ToggleRow(
                    title = stringResource(Res.string.show_avatars),
                    description = stringResource(Res.string.show_avatars_desc),
                    checked = showAvatars,
                    onChange = onShowAvatarsChange,
                )

                SelectorRow(
                    title = stringResource(Res.string.font_size),
                    value = fontSizeLabel(fontSize),
                    onClick = { onOpenDialog(ChatSettingDialog.FONT_SIZE) },
                )

                SelectorRow(
                    title = stringResource(Res.string.latex_renderer),
                    value = latexRendererLabel(latexRenderer),
                    onClick = { onOpenDialog(ChatSettingDialog.LATEX_RENDERER) },
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                GroupLabel(stringResource(Res.string.chat_group_behavior))

                ToggleRow(
                    title = stringResource(Res.string.auto_scroll),
                    description = stringResource(Res.string.auto_scroll_desc),
                    checked = autoScrollEnabled,
                    onChange = onAutoScrollChange,
                )

                ToggleRow(
                    title = stringResource(Res.string.show_thinking_blocks),
                    description = stringResource(Res.string.show_thinking_blocks_desc),
                    checked = showThinkingBlocks,
                    onChange = onShowThinkingChange,
                )

                ToggleRow(
                    title = stringResource(Res.string.show_image_descriptions),
                    description = stringResource(Res.string.show_image_descriptions_desc),
                    checked = showImageDescriptions,
                    onChange = onShowImageDescriptionsChange,
                )

                ToggleRow(
                    title = stringResource(Res.string.dismiss_keyboard_on_send),
                    description = stringResource(Res.string.dismiss_keyboard_on_send_desc),
                    checked = dismissKeyboardOnSend,
                    onChange = onDismissKeyboardOnSendChange,
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                GroupLabel(stringResource(Res.string.chat_group_header_context))

                SelectorRow(
                    title = stringResource(Res.string.chat_header_title),
                    value = stringResource(
                        Res.string.chat_header_summary_format,
                        chatHeaderContentLabel(chatHeaderContent),
                        chatHeaderAlignmentLabel(chatHeaderAlignment),
                    ),
                    onClick = { onOpenDialog(ChatSettingDialog.CHAT_HEADER) },
                )

                SelectorRow(
                    title = stringResource(Res.string.context_bar_title),
                    value = contextBarPlacementLabel(contextBarPlacement),
                    onClick = { onOpenDialog(ChatSettingDialog.CONTEXT_BAR) },
                )

                SelectorRow(
                    title = stringResource(Res.string.starred_models_title),
                    value = starredModelsDisplayLabel(starredModelsDisplay),
                    onClick = { onOpenDialog(ChatSettingDialog.STARRED_MODELS) },
                )
            }
        }
        HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
    }
}

/**
 * Compact clickable row for a dialog-backed setting: title with the currently
 * selected value beneath it, chevron on the right.
 */
@Composable
internal fun SelectorRow(
    title: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = value,
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

@Composable
internal fun GroupLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.width(16.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
        )
    }
}
