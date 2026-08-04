package com.garfiec.librechat.feature.chat.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.garfiec.librechat.feature.chat.resources.*
import com.garfiec.librechat.feature.chat.resources.Res
import org.jetbrains.compose.resources.stringResource

/**
 * Parsed citation found in message text.
 *
 * @property title Display title extracted from the source text (portion before any URL).
 * @property url URL extracted from the source text, if present.
 */
data class Citation(
    val number: Int,
    val source: String?,
    val title: String?,
    val url: String?,
    val fullMatch: String,
    val range: IntRange,
)

// Matches patterns like [1], [2], or unicode citation markers like 【1†source】
private val CITATION_REGEX = Regex(
    """\[(\d+)]|\u3010(\d+)\u2020([^\u3011]*)\u3011""",
)

/** Extracts a URL from a source string if one is embedded. */
private val URL_REGEX = Regex("""https?://\S+""")

private const val CITATION_TAG = "citation"

/**
 * Parses text for citation markers and returns an annotated string with
 * clickable citation chips, along with the list of detected citations.
 */
fun parseCitations(text: String): Pair<AnnotatedString, List<Citation>> {
    val citations = mutableListOf<Citation>()

    CITATION_REGEX.findAll(text).forEach { match ->
        val bracketNum = match.groupValues[1]
        val unicodeNum = match.groupValues[2]
        val source = match.groupValues[3].takeIf { it.isNotBlank() }

        val number = (bracketNum.ifEmpty { unicodeNum }).toIntOrNull() ?: return@forEach

        // Extract URL from source if present
        val sourceText = source?.trim()
        val extractedUrl = sourceText?.let { URL_REGEX.find(it)?.value }
        val title = if (extractedUrl != null) {
            sourceText?.replace(extractedUrl, "")?.trim()?.ifEmpty { null }
        } else {
            sourceText
        }

        citations.add(
            Citation(
                number = number,
                source = sourceText,
                title = title,
                url = extractedUrl,
                fullMatch = match.value,
                range = match.range,
            ),
        )
    }

    if (citations.isEmpty()) {
        return AnnotatedString(text) to emptyList()
    }

    val annotated = buildAnnotatedString {
        var lastIndex = 0
        citations.forEach { citation ->
            if (citation.range.first > lastIndex) {
                append(text.substring(lastIndex, citation.range.first))
            }
            pushStringAnnotation(tag = CITATION_TAG, annotation = citation.number.toString())
            withStyle(
                SpanStyle(
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.sp,
                ),
            ) {
                append("[${citation.number}]")
            }
            pop()
            lastIndex = citation.range.last + 1
        }
        if (lastIndex < text.length) {
            append(text.substring(lastIndex))
        }
    }

    return annotated to citations
}

/**
 * Renders text with inline citation chips that show details on tap.
 */
@Composable
fun CitationText(
    text: String,
    modifier: Modifier = Modifier,
    fontSizeMultiplier: Float = 1.0f,
) {
    val (annotatedString, citations) = remember(text) { parseCitations(text) }
    var activeCitation by remember { mutableStateOf<Citation?>(null) }

    val chipColor = MaterialTheme.colorScheme.primary
    val styledAnnotated = remember(annotatedString, chipColor) {
        buildAnnotatedString {
            append(annotatedString)
            annotatedString.getStringAnnotations(CITATION_TAG, 0, annotatedString.length)
                .forEach { annotation ->
                    addStyle(
                        SpanStyle(
                            color = chipColor,
                            background = chipColor.copy(alpha = 0.12f),
                        ),
                        annotation.start,
                        annotation.end,
                    )
                }
        }
    }

    val baseStyle = MaterialTheme.typography.bodyLarge.let { style ->
        if (fontSizeMultiplier == 1.0f) {
            style
        } else {
            style.copy(
                fontSize = (style.fontSize.value * fontSizeMultiplier).sp,
                lineHeight = (style.lineHeight.value * fontSizeMultiplier).sp,
            )
        }
    }

    if (citations.isEmpty()) {
        Text(
            text = text,
            style = baseStyle,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = modifier,
        )
        return
    }

    @Suppress("DEPRECATION")
    ClickableText(
        text = styledAnnotated,
        style = baseStyle.copy(
            color = MaterialTheme.colorScheme.onSurface,
        ),
        modifier = modifier,
        onClick = { offset ->
            styledAnnotated.getStringAnnotations(CITATION_TAG, offset, offset)
                .firstOrNull()
                ?.let { annotation ->
                    val citationNum = annotation.item.toIntOrNull()
                    activeCitation = citations.find { it.number == citationNum }
                }
        },
    )

    activeCitation?.let { citation ->
        Popup(
            onDismissRequest = { activeCitation = null },
            properties = PopupProperties(focusable = true),
        ) {
            // Own selection scope: a popup is a separate composition owner, so its text must not
            // join the message's registrar. See SubwindowSelectionContainer.
            SubwindowSelectionContainer {
                CitationPopup(
                    citation = citation,
                    onDismiss = { activeCitation = null },
                )
            }
        }
    }
}

@Composable
private fun CitationPopup(
    citation: Citation,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uriHandler = LocalUriHandler.current

    Surface(
        modifier = modifier.padding(8.dp),
        shape = RoundedCornerShape(8.dp),
        shadowElevation = 8.dp,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = stringResource(Res.string.citation_number, citation.number),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )

            // Show title if available (extracted separately from URL)
            val displayTitle = citation.title
            if (displayTitle != null) {
                Text(
                    text = displayTitle,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontWeight = FontWeight.Medium,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 4.dp),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // Show source text as fallback if no separate title was extracted
            if (displayTitle == null && citation.source != null) {
                Text(
                    text = citation.source,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // Show URL with clickable "Open" link
            val citationUrl = citation.url
            if (citationUrl != null) {
                Text(
                    text = citationUrl,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .clickable {
                            uriHandler.openUri(citationUrl)
                            onDismiss()
                        },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = stringResource(Res.string.cd_open_citation_url),
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = stringResource(Res.string.action_open),
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = MaterialTheme.colorScheme.primary,
                            textDecoration = TextDecoration.Underline,
                        ),
                    )
                }
            }
        }
    }
}
