package com.garfiec.librechat.feature.chat.util

import com.garfiec.librechat.core.model.Message
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MessageTreeTest {

    private fun message(
        id: String,
        parentId: String? = null,
        text: String = "msg-$id",
        isUser: Boolean = false,
    ) = Message(
        messageId = id,
        conversationId = "conv-1",
        parentMessageId = parentId,
        text = text,
        isCreatedByUser = isUser,
    )

    @Test
    fun `empty list returns empty path`() {
        val result = buildActiveMessagePath(emptyList())
        assertThat(result).isEmpty()
    }

    @Test
    fun `single message returns single node`() {
        val messages = listOf(message("a"))
        val result = buildActiveMessagePath(messages)
        assertThat(result).hasSize(1)
        assertThat(result[0].message.messageId).isEqualTo("a")
        assertThat(result[0].siblingIndex).isEqualTo(0)
        assertThat(result[0].siblingCount).isEqualTo(1)
    }

    @Test
    fun `linear conversation returns all messages in order`() {
        val messages = listOf(
            message("a"),
            message("b", parentId = "a"),
            message("c", parentId = "b"),
        )
        val result = buildActiveMessagePath(messages)
        assertThat(result).hasSize(3)
        assertThat(result.map { it.message.messageId }).containsExactly("a", "b", "c").inOrder()
    }

    @Test
    fun `branching conversation follows last child by default`() {
        // Root -> a, Root -> b (b is last, so default)
        val messages = listOf(
            message("a"),
            message("b"),
        )
        val result = buildActiveMessagePath(messages)
        assertThat(result).hasSize(1)
        // Both a and b are root-level; default selects last (b)
        assertThat(result[0].message.messageId).isEqualTo("b")
        assertThat(result[0].siblingCount).isEqualTo(2)
        assertThat(result[0].siblingIndex).isEqualTo(1)
    }

    @Test
    fun `activeBranches overrides default path`() {
        val rootParent = "00000000-0000-0000-0000-000000000000"
        val messages = listOf(
            message("a"),
            message("b"),
        )
        // Select the first sibling (index 0 = "a") instead of default last
        val result = buildActiveMessagePath(messages, activeBranches = mapOf(rootParent to 0))
        assertThat(result).hasSize(1)
        assertThat(result[0].message.messageId).isEqualTo("a")
    }

    @Test
    fun `sibling counts are correct`() {
        // Root has 3 children
        val messages = listOf(
            message("a"),
            message("b"),
            message("c"),
        )
        val result = buildActiveMessagePath(messages)
        assertThat(result).hasSize(1)
        assertThat(result[0].siblingCount).isEqualTo(3)
        // Children should also reflect the correct count
        assertThat(result[0].children).hasSize(3)
        result[0].children.forEachIndexed { index, node ->
            assertThat(node.siblingIndex).isEqualTo(index)
            assertThat(node.siblingCount).isEqualTo(3)
        }
    }

    @Test
    fun `deep branching with override follows correct path`() {
        val rootParent = "00000000-0000-0000-0000-000000000000"
        val messages = listOf(
            message("root1"),
            message("root2"),
            message("child1-of-root1", parentId = "root1"),
            message("child2-of-root1", parentId = "root1"),
            message("grandchild", parentId = "child1-of-root1"),
        )
        // Select root1 (index 0), then child1-of-root1 (index 0)
        val result = buildActiveMessagePath(
            messages,
            activeBranches = mapOf(rootParent to 0, "root1" to 0),
        )
        assertThat(result.map { it.message.messageId })
            .containsExactly("root1", "child1-of-root1", "grandchild")
            .inOrder()
    }

    // ── streamingLeafId (in-place streaming anchor) ──────────────────────

    @Test
    fun `streamingLeafId truncates path at the anchor, hiding the stale branch`() {
        // user -> oldAi (the stale response being regenerated). Anchoring at `user`
        // drops oldAi from the path so the streaming bubble renders in its place.
        val messages = listOf(
            message("user", isUser = true),
            message("oldAi", parentId = "user"),
        )
        val result = buildActiveMessagePath(messages, streamingLeafId = "user")
        assertThat(result.map { it.message.messageId }).containsExactly("user").inOrder()
    }

    @Test
    fun `streamingLeafId selects the anchor's branch over a stale last-child default`() {
        // parent -> b1 (old user edit, with an AI child), parent -> b2 (new edited
        // user message). Anchoring at b2 forces it as the visible branch and keeps
        // b1's stale AI child hidden.
        val messages = listOf(
            message("parent", isUser = true),
            message("b1", parentId = "parent", isUser = true),
            message("b1-ai", parentId = "b1"),
            message("b2", parentId = "parent", isUser = true),
        )
        val result = buildActiveMessagePath(messages, streamingLeafId = "b2")
        assertThat(result.map { it.message.messageId }).containsExactly("parent", "b2").inOrder()
    }

    @Test
    fun `streamingLeafId overrides a conflicting activeBranches selection along the chain`() {
        // An activeBranches override pins the old sibling, but the streaming anchor
        // must win so the freshly edited branch shows during the stream.
        val messages = listOf(
            message("parent", isUser = true),
            message("b1", parentId = "parent", isUser = true),
            message("b2", parentId = "parent", isUser = true),
        )
        val result = buildActiveMessagePath(
            messages,
            activeBranches = mapOf("parent" to 0), // pins b1
            streamingLeafId = "b2",
        )
        assertThat(result.map { it.message.messageId }).containsExactly("parent", "b2").inOrder()
    }

    @Test
    fun `unknown streamingLeafId falls back to the normal activeBranches path`() {
        val messages = listOf(
            message("a", isUser = true),
            message("b", parentId = "a"),
        )
        val result = buildActiveMessagePath(messages, streamingLeafId = "does-not-exist")
        assertThat(result.map { it.message.messageId }).containsExactly("a", "b").inOrder()
    }
}
