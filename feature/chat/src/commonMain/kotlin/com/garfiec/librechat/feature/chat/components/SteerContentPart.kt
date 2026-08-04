package com.garfiec.librechat.feature.chat.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.garfiec.librechat.core.ui.components.AvatarImage
import com.garfiec.librechat.core.ui.components.endpointIconPainter
import com.garfiec.librechat.core.ui.components.isMonochromeEndpointIcon
import com.garfiec.librechat.feature.chat.resources.*
import com.garfiec.librechat.feature.chat.resources.Res
import com.garfiec.librechat.feature.chat.util.SegmentAuthor
import org.jetbrains.compose.resources.stringResource

/**
 * A mid-run steer, rendered as a user turn at the point in the response where the words entered
 * the run — which is also the order the next turn replays them in, since the server splits a
 * `steer` part back into a human message.
 *
 * Without a renderer the part stays parked as an unrendered raw element and the text vanishes from
 * the reloaded transcript, so a reply the user redirected reads as if they never said anything.
 */
@Composable
internal fun SteerContentPart(
    text: String,
    userName: String?,
    userAvatarUrl: String?,
    modifier: Modifier = Modifier,
    fontSizeMultiplier: Float = 1.0f,
    useKatex: Boolean = false,
) {
    if (text.isBlank()) return

    Column(modifier = modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        DisableSelection {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AvatarImage(
                    imageUrl = userAvatarUrl,
                    fallbackText = userName ?: stringResource(Res.string.sender_you),
                    showPersonIcon = userAvatarUrl == null,
                    size = 22.dp,
                    // The label beside it already says the name; AvatarImage otherwise defaults to
                    // "<name> avatar" and TalkBack would read the author twice.
                    contentDescription = null,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    // A heading, so TalkBack's next-heading gesture steps between the author changes
                    // in a long response instead of scrubbing through every line.
                    modifier = Modifier.semantics { heading() },
                    text = userName ?: stringResource(Res.string.sender_you),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.width(6.dp))
                // Says why a user message is sitting inside the response.
                Text(
                    text = stringResource(Res.string.steer_sent_during_reply),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        MarkdownContent(
            text = text,
            fontSizeMultiplier = fontSizeMultiplier,
            useKatex = useKatex,
        )
    }
}

/**
 * Restates who is speaking for content that resumes after a steer. The bubble's own header only
 * renders once, at the top, so without this the response's continuation reads as more of the
 * user's message.
 */
@Composable
internal fun SegmentAuthorHeader(
    author: SegmentAuthor,
    messageSender: String?,
    messageIconUrl: String?,
    messageEndpoint: String?,
    modifier: Modifier = Modifier,
) {
    // The run had already handed off when the steer landed, so the resumed content belongs to a
    // different agent than the message header names. No name is on the wire here — only the id —
    // and pairing that id with the message's own avatar would put the previous agent's face on it,
    // which is worse than showing no face. Resolving the name needs an agents lookup this
    // composable has no access to; until then it is a neutral badge over the raw id.
    val isOtherAgent = author is SegmentAuthor.Agent
    val label = when (author) {
        is SegmentAuthor.Agent -> author.agentId
        SegmentAuthor.Message -> messageSender ?: stringResource(Res.string.sender_assistant)
    }
    DisableSelection {
        Row(
            modifier = modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AvatarImage(
                imageUrl = if (isOtherAgent) null else messageIconUrl,
                fallbackText = label,
                fallbackIconPainter = when {
                    isOtherAgent -> rememberVectorPainter(Icons.Default.SmartToy)
                    messageIconUrl == null -> endpointIconPainter(messageEndpoint)
                    else -> null
                },
                tintIcon = isOtherAgent || (messageIconUrl == null && isMonochromeEndpointIcon(messageEndpoint)),
                size = 22.dp,
                contentDescription = null,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                modifier = Modifier.semantics { heading() },
                text = label,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
