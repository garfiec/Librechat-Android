package com.garfiec.librechat.feature.chat.util

import com.garfiec.librechat.core.model.ContentType
import com.garfiec.librechat.core.model.content.MessageContentPart

/**
 * Derives one comparison pane's message list. v0.8.7 persists a comparison as a
 * single response message whose content parts each carry an `agentId` (the added
 * agent suffixed `____N`), so for any such message we keep only [secondary]'s parts
 * (see [partsForPane]) — this restores the dual-pane view for *every* comparison turn
 * in history, including on reopen. The captured streaming buffer ([finalContent]) is
 * used only as a fallback: for the brief Final→reload gap before the attributed
 * server message arrives, or if a pane ended up with no attributed parts.
 * Also updates the sender name so the bubble shows the correct model.
 */
fun buildComparisonDisplayMessages(
    displayMessages: List<MessageNode>,
    secondary: Boolean,
    parallelMessageId: String?,
    finalContent: String?,
    senderName: String?,
): List<MessageNode> {
    return displayMessages.map { node ->
        val message = node.message
        when {
            hasParallelParts(message) -> {
                val paneParts = partsForPane(message, secondary)
                val content = if (paneParts.isEmpty() && !finalContent.isNullOrBlank()) {
                    listOf(MessageContentPart(type = ContentType.TEXT, text = finalContent))
                } else {
                    paneParts
                }
                node.copy(
                    message = message.copy(content = content, sender = senderName ?: message.sender),
                )
            }
            // Final→reload gap: the server message isn't parallel-attributed yet, so fall
            // back to this pane's captured streaming buffer.
            message.messageId == parallelMessageId && !finalContent.isNullOrBlank() -> {
                node.copy(
                    message = message.copy(
                        content = listOf(MessageContentPart(type = ContentType.TEXT, text = finalContent)),
                        sender = senderName ?: message.sender,
                    ),
                )
            }
            else -> node
        }
    }
}

/**
 * Collapses any parallel (Compare Models) message to just its primary agent's parts,
 * so an old comparison turn never renders both agents' content concatenated in the
 * single (non-comparison) list — e.g. after branching, or when viewing a comparison
 * sibling that isn't the active-path tail.
 */
fun collapseParallelToPrimary(displayMessages: List<MessageNode>): List<MessageNode> =
    displayMessages.map { node ->
        if (hasParallelParts(node.message)) {
            node.copy(message = node.message.copy(content = partsForPane(node.message, secondary = false)))
        } else {
            node
        }
    }
