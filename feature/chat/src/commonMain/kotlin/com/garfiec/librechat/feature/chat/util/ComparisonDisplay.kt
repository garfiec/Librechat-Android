package com.garfiec.librechat.feature.chat.util

import com.garfiec.librechat.core.model.ContentType
import com.garfiec.librechat.core.model.Message
import com.garfiec.librechat.core.model.content.MessageContentPart

/**
 * Derives one comparison pane's message list. v0.8.7 persists a comparison as a
 * single response message whose content parts each carry an `agentId` (the added
 * agent suffixed `____N`), so for any such message we keep only [secondary]'s parts
 * (see [partsForPane]) — this restores the dual-pane view for *every* comparison turn
 * in history, including on reopen. The captured streaming buffer ([finalContent]) is
 * used only as a fallback: for the brief Final→reload gap before the attributed
 * server message arrives, or if a pane ended up with no attributed parts.
 *
 * The persisted message carries only the *primary* agent's `endpoint`/`iconURL`, so
 * on the [secondary] pane the bubble avatar would otherwise duplicate the primary's.
 * Pass [secondaryEndpoint]/[secondaryIconUrl] to re-point the secondary pane's icon at
 * its own agent (icon precedence in the bubble is `iconURL` → endpoint fallback, so a
 * null [secondaryIconUrl] falls back to [secondaryEndpoint]'s icon). Also updates the
 * sender name so the bubble shows the correct model.
 */
fun buildComparisonDisplayMessages(
    displayMessages: List<MessageNode>,
    secondary: Boolean,
    parallelMessageId: String?,
    finalContent: String?,
    senderName: String?,
    secondaryEndpoint: String? = null,
    secondaryIconUrl: String? = null,
): List<MessageNode> {
    fun Message.forPane(newContent: List<MessageContentPart>): Message {
        val base = copy(content = newContent, sender = senderName ?: sender)
        // Only the secondary pane needs re-pointing; the primary message already carries
        // its own endpoint/iconURL. Force iconURL (even to null) so the primary's avatar
        // never bleeds into the secondary pane.
        return if (secondary) {
            base.copy(endpoint = secondaryEndpoint ?: base.endpoint, iconURL = secondaryIconUrl)
        } else {
            base
        }
    }
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
                node.copy(message = message.forPane(content))
            }
            // Final→reload gap: the server message isn't parallel-attributed yet, so fall
            // back to this pane's captured streaming buffer.
            message.messageId == parallelMessageId && !finalContent.isNullOrBlank() -> {
                node.copy(
                    message = message.forPane(
                        listOf(MessageContentPart(type = ContentType.TEXT, text = finalContent)),
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
