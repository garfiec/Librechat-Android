package com.garfiec.librechat.feature.chat.util

import androidx.compose.runtime.Immutable
import com.garfiec.librechat.core.model.Message
import com.garfiec.librechat.core.model.request.NO_PARENT

@Immutable
data class MessageNode(
    val message: Message,
    val children: List<MessageNode>,
    val siblingIndex: Int,
    val siblingCount: Int,
    /** The key used in the tree grouping — may differ from message.parentMessageId
     *  when orphan re-parenting moves the message under NO_PARENT. Use this as the
     *  key for activeBranches in switchBranch(). */
    val treeParentKey: String = NO_PARENT,
)

/**
 * Build the "active path" from a flat list of messages using parentMessageId.
 *
 * Groups messages by parentMessageId, then walks from the root following the
 * selected (or last) child at each level. Returns an ordered list of
 * [MessageNode]s representing the currently visible conversation thread.
 *
 * @param messages flat list of messages from the API
 * @param activeBranches map of parentMessageId -> selected child index;
 *        when absent, the last child (most recent) is shown
 * @param streamingLeafId when a response is streaming into a known message, the
 *        path is forced onto that message's ancestor chain and truncated there.
 *        The in-flight reply attaches as its (not-yet-persisted) child, which the
 *        UI renders below the path as the streaming bubble — the mobile analog of
 *        the web client's empty `initialResponse` placeholder node. This overrides
 *        [activeBranches] along the chain so a freshly edited/regenerated branch is
 *        shown immediately instead of the stale one. Ignored if the id isn't present
 *        (e.g. an optimistic message not yet inserted), in which case the normal
 *        activeBranches walk applies.
 */
fun buildActiveMessagePath(
    messages: List<Message>,
    activeBranches: Map<String, Int> = emptyMap(),
    streamingLeafId: String? = null,
): List<MessageNode> {
    if (messages.isEmpty()) return emptyList()

    // Group children by their parentMessageId
    val childrenByParent = mutableMapOf<String, MutableList<Message>>()
    val messageIds = messages.mapTo(mutableSetOf()) { it.messageId }
    for (message in messages) {
        val parentId = message.parentMessageId
            ?.takeIf { it != NO_PARENT && it.isNotBlank() }
            ?: NO_PARENT
        childrenByParent.getOrPut(parentId) { mutableListOf() }.add(message)
    }

    // Re-parent orphans: messages whose parentId isn't NO_PARENT and isn't
    // in the message set (e.g. system/root messages not returned by the API).
    // Move them under NO_PARENT so the tree walk can find them.
    val orphanParents = childrenByParent.keys
        .filter { it != NO_PARENT && it !in messageIds }
    if (orphanParents.isNotEmpty()) {
        val roots = childrenByParent.getOrPut(NO_PARENT) { mutableListOf() }
        for (orphanParentId in orphanParents) {
            childrenByParent.remove(orphanParentId)?.let { roots.addAll(it) }
        }
    }

    // Ancestor chain of the streaming leaf (inclusive), so the walk can follow it
    // and stop there regardless of activeBranches. Each level on the way to the leaf
    // has exactly one sibling in this set.
    val leafChain: Set<String> = if (streamingLeafId != null && streamingLeafId in messageIds) {
        val byId = messages.associateBy { it.messageId }
        buildSet {
            var id: String? = streamingLeafId
            while (id != null && id != NO_PARENT && id.isNotBlank() && add(id)) {
                id = byId[id]?.parentMessageId
            }
        }
    } else {
        emptySet()
    }

    // Walk the tree from roots, building the flat active path
    val result = mutableListOf<MessageNode>()
    var currentParentId = NO_PARENT

    while (true) {
        val siblings = childrenByParent[currentParentId] ?: break
        if (siblings.isEmpty()) break

        val siblingCount = siblings.size
        // The streaming chain wins, then an explicit activeBranches override, then
        // the last child (most recent).
        val chainIndex = if (leafChain.isEmpty()) -1 else siblings.indexOfFirst { it.messageId in leafChain }
        val selectedIndex = when {
            chainIndex >= 0 -> chainIndex
            else -> activeBranches[currentParentId]?.coerceIn(0, siblingCount - 1) ?: (siblingCount - 1)
        }

        val selectedMessage = siblings[selectedIndex]

        // Build children list for this node (all siblings as MessageNode without recursion)
        val childrenNodes = siblings.mapIndexed { index, msg ->
            MessageNode(
                message = msg,
                children = emptyList(),
                siblingIndex = index,
                siblingCount = siblingCount,
                treeParentKey = currentParentId,
            )
        }

        result.add(
            MessageNode(
                message = selectedMessage,
                children = childrenNodes,
                siblingIndex = selectedIndex,
                siblingCount = siblingCount,
                treeParentKey = currentParentId,
            )
        )

        // Stop at the streaming leaf: its children (if any) are the stale branch
        // being replaced; the in-flight reply renders as its pending child instead.
        if (selectedMessage.messageId == streamingLeafId) break

        // Follow the selected child's subtree
        currentParentId = selectedMessage.messageId
    }

    return result
}
