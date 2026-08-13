package com.garfiec.librechat.feature.chat.util

import com.garfiec.librechat.core.model.ContentType
import com.garfiec.librechat.core.model.content.AgentToolCall
import com.garfiec.librechat.core.model.content.MessageContentPart
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Parallel (Compare Models) lanes render raw parts, matching upstream: `ContentParts.tsx`
 * early-returns any content carrying a `groupId` to `ParallelContentRenderer`, which calls the
 * bare `renderPart` — no `groupSequentialToolCalls`, and no `hideAttachments`.
 */
class ParallelGroupingTest {

    private val primaryAgent = "anthropic__claude-haiku-4-5"
    private val addedAgent = "anthropic__claude-haiku-4-5____1"

    private fun tool(id: String, agentId: String? = null, groupId: Int? = null) =
        MessageContentPart(
            type = ContentType.TOOL_CALL,
            toolCall = AgentToolCall(id = id, name = "image_gen", output = "done"),
            agentId = agentId,
            groupId = groupId,
        )

    private fun label(text: String, agentId: String? = null, groupId: Int? = null) =
        MessageContentPart(
            type = ContentType.ACTIVITY_LABEL,
            activityLabel = text,
            agentId = agentId,
            groupId = groupId,
        )

    private fun text(value: String, groupId: Int? = null) =
        MessageContentPart(type = ContentType.TEXT, text = value, groupId = groupId)

    private fun steer(value: String) =
        MessageContentPart(type = ContentType.STEER, steer = JsonPrimitive(value))

    /**
     * Mirrors `MessageContentAndActions`'s grouping call site — a predicate that is right in
     * isolation but never reaches the call site would still ship the bug.
     */
    private fun renderGroups(parts: List<MessageContentPart>): List<ContentGroup> =
        groupContentParts(parts, groupActivity = !hasParallelGroupIds(parts))
            .flatMap { it.groups }

    @Test
    fun parallelLaneRendersEveryPartUngrouped() {
        val groups = renderGroups(
            listOf(
                tool("t1", addedAgent, groupId = 1),
                tool("t2", addedAgent, groupId = 1),
            ),
        )
        assertEquals(2, groups.size, "each part renders on its own in a parallel lane")
        assertTrue(groups.all { it is ContentGroup.Single })
    }

    @Test
    fun ordinaryContentStillGroupsSoTheMainThreadIsUnchanged() {
        val groups = renderGroups(listOf(tool("t1"), tool("t2")))
        assertEquals(1, groups.size)
        assertTrue(groups.single() is ContentGroup.Activity, "no groupId means the unchanged path")
    }

    /** A real server persists `groupId` on every part of a comparison message, primary and added alike. */
    @Test
    fun primaryLaneIsDetectedByGroupIdNotTheAgentSuffix() {
        val primaryLane = listOf(
            tool("t1", primaryAgent, groupId = 1),
            tool("t2", primaryAgent, groupId = 1),
        )
        assertTrue(
            primaryLane.none { isAddedAgentId(it.agentId) },
            "no suffixed part survives the pane filter",
        )
        assertTrue(hasParallelGroupIds(primaryLane), "groupId is what still identifies the lane")
        assertTrue(renderGroups(primaryLane).all { it is ContentGroup.Single })
    }

    /**
     * Hoisting is driven entirely by `ContentGroup.Activity` — the only branch that collects a
     * block's attachments and the only one that renders its parts with `hideAttachments = true`.
     * No Activity group therefore means no hoist and no suppression.
     */
    @Test
    fun parallelLaneFormsNoActivityGroupSoNothingHoistsAttachments() {
        val groups = renderGroups(
            listOf(
                tool("t1", addedAgent, groupId = 1),
                tool("t2", addedAgent, groupId = 1),
                label("Generated 2 images", addedAgent, groupId = 1),
            ),
        )
        assertTrue(
            groups.filterIsInstance<ContentGroup.Activity>().isEmpty(),
            "an Activity group would both hoist and suppress, which is the double-render risk",
        )
    }

    /**
     * A filled label is consumed into an activity header on the grouped path, so flattening groups
     * back into parts would silently drop it. Bypassing grouping at the source keeps it as its own
     * part, where `OrphanActivityLabel` renders it.
     */
    @Test
    fun parallelLaneKeepsAFilledActivityLabelAsItsOwnPart() {
        val groups = renderGroups(
            listOf(
                tool("t1", addedAgent, groupId = 1),
                tool("t2", addedAgent, groupId = 1),
                label("Generated 2 images", addedAgent, groupId = 1),
            ),
        )
        assertEquals(3, groups.size, "the label is a part of its own, not a consumed header")
        val labelPart = groups.map { (it as ContentGroup.Single).entry.part }
            .single { it.type == ContentType.ACTIVITY_LABEL }
        assertEquals("Generated 2 images", labelPart.activityLabelText())
    }

    /** Only grouping is bypassed; steer segmentation is upstream's parallel path too. */
    @Test
    fun parallelLaneStillSegmentsAtSteers() {
        val parts = listOf(
            text("before", groupId = 1),
            steer("actually, use metric"),
            text("after", groupId = 1),
        )
        val segments = groupContentParts(parts, groupActivity = !hasParallelGroupIds(parts))
        assertEquals(2, segments.size, "a steer still splits the lane into attributed segments")
    }

    @Test
    fun contentWithoutAnyGroupIdIsNotAParallelLane() {
        assertTrue(!hasParallelGroupIds(listOf(tool("t1"), text("hi"))))
    }
}
