package com.librechat.android.feature.voice.screen

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography

/**
 * Renders voice transcript lines with CommonMark (bold, italic, inline code, etc.).
 */
@Composable
internal fun VoiceTranscriptMarkdown(
    text: String,
    modifier: Modifier = Modifier,
) {
    val mdColors = markdownColor(
        text = MaterialTheme.colorScheme.onSurface,
        codeText = MaterialTheme.colorScheme.onSurface,
        linkText = MaterialTheme.colorScheme.primary,
        codeBackground = MaterialTheme.colorScheme.surfaceContainerHigh,
        inlineCodeBackground = MaterialTheme.colorScheme.surfaceContainerHigh,
        dividerColor = MaterialTheme.colorScheme.outlineVariant,
    )
    val body = MaterialTheme.typography.bodyMedium
    val mdTypography = markdownTypography(
        h1 = MaterialTheme.typography.titleLarge,
        h2 = MaterialTheme.typography.titleMedium,
        h3 = MaterialTheme.typography.titleSmall,
        h4 = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        h5 = MaterialTheme.typography.labelLarge,
        h6 = MaterialTheme.typography.labelLarge,
        text = body,
        paragraph = body,
        quote = body.copy(fontStyle = FontStyle.Italic),
        code = body.copy(fontFamily = FontFamily.Monospace),
        inlineCode = body.copy(fontFamily = FontFamily.Monospace),
        ordered = body,
        bullet = body,
        list = body,
    )
    Markdown(
        content = text,
        colors = mdColors,
        typography = mdTypography,
        modifier = modifier,
    )
}
