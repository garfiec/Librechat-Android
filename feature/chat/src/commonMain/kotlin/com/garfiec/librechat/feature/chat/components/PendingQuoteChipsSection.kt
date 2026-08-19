package com.garfiec.librechat.feature.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.garfiec.librechat.feature.chat.resources.Res
import com.garfiec.librechat.feature.chat.resources.cd_remove_quote
import org.jetbrains.compose.resources.stringResource

/**
 * Excerpts staged via the selection toolbar's "Add to chat" (v0.8.7 quotes), pinned above the
 * composer until the next fresh send takes them onto the user message.
 *
 * Reuses [MessageQuotes]' left-accent visual language — these chips become exactly those blocks
 * on the sent bubble — but compact: one single-line truncated row per excerpt, each with its ×.
 * Sits above the steer chips and the queue: quotes attach to the message being *composed*,
 * which precedes everything already dispatched.
 */
@Composable
fun PendingQuoteChipsSection(
    pendingQuotes: List<String>,
    onRemove: (index: Int) -> Unit,
    modifier: Modifier = Modifier,
    fontSizeMultiplier: Float = 1f,
) {
    if (pendingQuotes.isEmpty()) return

    val accent = MaterialTheme.colorScheme.primary
    val background = MaterialTheme.colorScheme.surfaceVariant
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        pendingQuotes.forEachIndexed { index, quote ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min)
                    .background(background, RoundedCornerShape(6.dp)),
            ) {
                Spacer(
                    modifier = Modifier
                        .width(3.dp)
                        .fillMaxHeight()
                        .background(accent),
                )
                Text(
                    text = quote,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp * fontSizeMultiplier,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                )
                IconButton(onClick = { onRemove(index) }) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(Res.string.cd_remove_quote),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}
