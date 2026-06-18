package com.garfiec.librechat.feature.chat.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.garfiec.librechat.feature.chat.resources.Res
import com.garfiec.librechat.feature.chat.resources.cd_cancel_queued_message
import com.garfiec.librechat.feature.chat.resources.cd_reorder_queued_message
import com.garfiec.librechat.feature.chat.resources.queued_attachment_only
import com.garfiec.librechat.feature.chat.viewmodel.QueuedMessage
import org.jetbrains.compose.resources.stringResource

/**
 * The "ghost" queue pinned directly above the composer: dimmed previews of follow-up messages
 * the user has queued, waiting to auto-send (FIFO) as each reply completes. These are NOT part
 * of the message tree — they live purely in [QueuedMessage] UI state. Hosted by the chat input
 * (not the scrolling message list) so the list's auto-scroll-to-bottom can't make them bounce
 * as the reply streams.
 *
 * Tap a row to pull it back into the composer for editing; the × cancels it; long-press anywhere
 * on a row (the drag handle marks the affordance) and drag to reorder.
 *
 * The drag gesture is owned by the **container** Column — a single [pointerInput] that never gets
 * torn down as rows reorder — rather than by per-row nodes (which Compose detaches when a keyed
 * child moves, killing an in-progress gesture). The container hit-tests the grabbed row from
 * measured row bounds and swaps with a neighbour once the finger passes that neighbour's midpoint,
 * so variable-height rows reorder correctly. No external reorderable dependency, no nested scroll
 * to fight: the section is hosted in a bottom-anchored input bar that simply grows upward.
 */
@Composable
fun QueuedMessagesSection(
    queuedMessages: List<QueuedMessage>,
    onEdit: (String) -> Unit,
    onCancel: (String) -> Unit,
    onReorder: (fromIndex: Int, toIndex: Int) -> Unit,
    modifier: Modifier = Modifier,
    fontSizeMultiplier: Float = 1f,
) {
    if (queuedMessages.isEmpty()) return

    // Measured per-row geometry (localId -> top Y / height, in this Column's coordinate space),
    // recorded by each row via onGloballyPositioned and read by the container's drag hit-testing.
    val rowTops = remember { mutableStateMapOf<String, Float>() }
    val rowHeights = remember { mutableStateMapOf<String, Float>() }
    var draggingId by remember { mutableStateOf<String?>(null) }
    // Live translation of the dragged row from its laid-out slot, so it follows the finger.
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    // Snapshot the list for the gesture closure so it always reads the current order.
    val currentQueue by rememberUpdatedState(queuedMessages)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .pointerInput(Unit) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { offset ->
                        draggingId = currentQueue.firstOrNull { item ->
                            val top = rowTops[item.localId] ?: return@firstOrNull false
                            val height = rowHeights[item.localId] ?: 0f
                            offset.y >= top && offset.y < top + height
                        }?.localId
                        dragOffsetY = 0f
                    },
                    onDragEnd = {
                        draggingId = null
                        dragOffsetY = 0f
                    },
                    onDragCancel = {
                        draggingId = null
                        dragOffsetY = 0f
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        val id = draggingId ?: return@detectDragGesturesAfterLongPress
                        val list = currentQueue
                        val from = list.indexOfFirst { it.localId == id }
                        if (from < 0) return@detectDragGesturesAfterLongPress
                        dragOffsetY += dragAmount.y
                        // Swap with a neighbour once the finger crosses that neighbour's midpoint.
                        if (dragAmount.y > 0 && from < list.lastIndex) {
                            val nextHeight = rowHeights[list[from + 1].localId] ?: 0f
                            if (dragOffsetY > nextHeight / 2f) {
                                onReorder(from, from + 1)
                                dragOffsetY -= nextHeight
                            }
                        } else if (dragAmount.y < 0 && from > 0) {
                            val prevHeight = rowHeights[list[from - 1].localId] ?: 0f
                            if (dragOffsetY < -prevHeight / 2f) {
                                onReorder(from, from - 1)
                                dragOffsetY += prevHeight
                            }
                        }
                    },
                )
            },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        queuedMessages.forEach { item ->
            val isDragging = draggingId == item.localId
            QueuedMessageRow(
                item = item,
                isDragging = isDragging,
                fontSizeMultiplier = fontSizeMultiplier,
                onTap = { onEdit(item.localId) },
                onCancel = { onCancel(item.localId) },
                modifier = Modifier
                    .onGloballyPositioned { coords ->
                        rowTops[item.localId] = coords.positionInParent().y
                        rowHeights[item.localId] = coords.size.height.toFloat()
                    }
                    // Lift the dragged row above its peers and let it follow the finger.
                    .zIndex(if (isDragging) 1f else 0f)
                    .graphicsLayer { translationY = if (isDragging) dragOffsetY else 0f },
            )
        }
    }
}

@Composable
private fun QueuedMessageRow(
    item: QueuedMessage,
    isDragging: Boolean,
    fontSizeMultiplier: Float,
    onTap: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (isDragging) 0.95f else 0.55f),
        tonalElevation = if (isDragging) 6.dp else 0.dp,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onTap)
                .padding(start = 8.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        ) {
            Icon(
                imageVector = Icons.Default.DragHandle,
                contentDescription = stringResource(Res.string.cd_reorder_queued_message),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))

            if (item.attachments.isNotEmpty()) {
                Icon(
                    imageVector = Icons.Default.AttachFile,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
            }

            // Attachment-only queued items have no text — show a placeholder so the row isn't blank.
            val isPlaceholder = item.text.isBlank()
            Text(
                text = if (isPlaceholder) stringResource(Res.string.queued_attachment_only) else item.text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (isPlaceholder) 0.6f else 0.85f),
                fontSize = 14.sp * fontSizeMultiplier,
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 2.dp),
            )

            IconButton(
                onClick = onCancel,
                modifier = Modifier.alpha(0.8f),
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(Res.string.cd_cancel_queued_message),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}
