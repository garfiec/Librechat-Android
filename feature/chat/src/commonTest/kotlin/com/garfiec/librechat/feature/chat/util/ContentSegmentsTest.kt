package com.garfiec.librechat.feature.chat.util

import com.garfiec.librechat.core.model.ContentType
import com.garfiec.librechat.core.model.content.AgentToolCall
import com.garfiec.librechat.core.model.content.AgentUpdateContent
import com.garfiec.librechat.core.model.content.MessageContentPart
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContentSegmentsTest {

    private fun text(value: String) = MessageContentPart(type = ContentType.TEXT, text = value)

    private fun think(value: String) = MessageContentPart(type = ContentType.THINK, think = value)

    private fun tool(id: String, name: String = "search", output: String? = "done") =
        MessageContentPart(
            type = ContentType.TOOL_CALL,
            toolCall = AgentToolCall(id = id, name = name, output = output),
        )

    private fun subagent(id: String, nested: List<MessageContentPart>) =
        MessageContentPart(
            type = ContentType.TOOL_CALL,
            toolCall = AgentToolCall(id = id, name = "subagent", output = "done", subagentContent = nested),
        )

    private fun label(
        text: String?,
        pending: Boolean? = null,
        status: String? = null,
        toolCallIds: List<String>? = null,
    ) = MessageContentPart(
        type = ContentType.ACTIVITY_LABEL,
        activityLabel = text,
        pending = pending,
        status = status,
        toolCallIds = toolCallIds,
    )

    private fun steer(value: String) =
        MessageContentPart(type = ContentType.STEER, steer = JsonPrimitive(value))

    private fun handoff(agentId: String) = MessageContentPart(
        type = ContentType.AGENT_UPDATE,
        agentUpdate = AgentUpdateContent(agentId = agentId),
    )

    private fun onlySegment(parts: List<MessageContentPart>): ContentSegment {
        val segments = groupContentParts(parts)
        assertEquals(1, segments.size, "expected a single segment")
        return segments.single()
    }

    // ─── segmentation ───────────────────────────────────────────────

    @Test
    fun noSteers_produceExactlyOneUnattributedSegment() {
        val segment = onlySegment(listOf(text("hello"), tool("t1"), text("bye")))
        assertNull(segment.author, "the bubble's own header already names the author")
    }

    @Test
    fun emptyContent_producesNoSegments() {
        assertEquals(emptyList(), groupContentParts(emptyList()))
    }

    @Test
    fun aSteerSplitsTheMessageAndReattributesWhatResumes() {
        val segments = groupContentParts(listOf(text("before"), steer("do it differently"), text("after")))

        assertEquals(2, segments.size)
        assertNull(segments[0].author)
        assertEquals(SegmentAuthor.Message, segments[1].author)
        // The steer terminates the segment it interrupted, so the user's words stay in reading
        // order rather than opening the resumed one.
        assertEquals(listOf(0, 1), segments[0].groups.flatMap { g -> g.entries.map { it.index } })
        assertEquals(listOf(2), segments[1].groups.flatMap { g -> g.entries.map { it.index } })
    }

    @Test
    fun consecutiveSteersOpenOneSegmentNotTwo() {
        // Two steers sent back to back are one interruption, so the attribution is restated once.
        val segments = groupContentParts(
            listOf(text("a"), steer("first"), steer("second"), text("b")),
        )
        assertEquals(2, segments.size)
        assertEquals(listOf(0, 1, 2), segments[0].groups.flatMap { g -> g.entries.map { it.index } })
        assertEquals(listOf(3), segments[1].groups.flatMap { g -> g.entries.map { it.index } })
    }

    @Test
    fun aTrailingSteerDoesNotOpenAnEmptySegment() {
        val segments = groupContentParts(listOf(text("a"), steer("last word")))
        assertEquals(1, segments.size)
    }

    @Test
    fun resumeAfterAHandoffIsAttributedToTheAgentThatTookOver() {
        val segments = groupContentParts(
            listOf(text("a"), handoff("agent_9"), steer("switch topic"), text("b")),
        )
        assertEquals(SegmentAuthor.Agent("agent_9"), segments[1].author)
    }

    @Test
    fun aHandoffAtTheResumePointKeepsThePreHandoffAuthor() {
        // The agent-update part announces the transition itself; restating the NEW agent above it
        // would attribute the handoff marker to the agent it is handing off to.
        val segments = groupContentParts(listOf(text("a"), steer("go"), handoff("agent_2"), text("b")))
        assertEquals(SegmentAuthor.Message, segments[1].author)
    }

    @Test
    fun aGroupNeverSpansASteer() {
        val segments = groupContentParts(
            listOf(tool("t1"), tool("t2"), steer("stop"), tool("t3"), tool("t4")),
        )
        assertEquals(2, segments.size)
        segments.forEach { segment ->
            segment.groups.filterIsInstance<ContentGroup.Activity>().forEach { group ->
                assertTrue(group.entries.none { it.part.type == ContentType.STEER })
            }
        }
    }

    @Test
    fun steerText_readsThePayloadAndRejectsWhatIsNotAString() {
        assertEquals("do it differently", steer("do it differently").steerText())
        assertNull(MessageContentPart(type = ContentType.STEER, steer = null).steerText())
        assertNull(steer("   ").steerText())
        // Parked as a raw element precisely so an unexpected shape degrades instead of failing
        // the whole message decode.
        assertNull(
            MessageContentPart(type = ContentType.STEER, steer = JsonPrimitive(7)).steerText(),
        )
    }

    // ─── grouping ───────────────────────────────────────────────────

    @Test
    fun aLabelClaimsItsBatchIncludingTheLeadingReasoning() {
        val segment = onlySegment(listOf(think("planning"), tool("t1"), label("Searched the codebase")))

        val group = segment.groups.single() as ContentGroup.Activity
        assertEquals("Searched the codebase", group.labelText)
        assertEquals(listOf(0, 1), group.entries.map { it.index })
        assertEquals(1, group.toolCount, "absorbed reasoning is not a tool")
    }

    @Test
    fun aLabelThatNeverArrivesFallsBackToLegacyGrouping() {
        // Reasoning renders standalone in its original position and only runs of two or more
        // tools group — exactly what the feature-off path does.
        val segment = onlySegment(listOf(think("planning"), tool("t1"), tool("t2")))

        assertEquals(2, segment.groups.size)
        assertTrue(segment.groups[0] is ContentGroup.Single)
        val group = segment.groups[1] as ContentGroup.Activity
        assertEquals("", group.labelText)
        assertEquals(listOf(1, 2), group.entries.map { it.index })
    }

    @Test
    fun asingleUnlabeledToolCallDoesNotGroup() {
        val segment = onlySegment(listOf(tool("t1")))
        assertTrue(segment.groups.single() is ContentGroup.Single)
    }

    @Test
    fun aPendingLabelIsInvisibleAndDoesNotFormAGroup() {
        // The reservation publishes at the batch boundary, before the text exists. Rendering it
        // would wrap a lone tool call under an empty header mid-run.
        val segment = onlySegment(listOf(tool("t1"), label(null, pending = true)))

        assertEquals(1, segment.groups.size)
        assertTrue(segment.groups.single() is ContentGroup.Single)
        assertTrue(segment.groups.none { g -> g.entries.any { it.part.type == ContentType.ACTIVITY_LABEL } })
    }

    @Test
    fun aBlankLabelStillMergesItsBatchWithTheNextOne() {
        // Two one-call batches whose first label stayed empty must render as ONE legacy group,
        // not two standalone cards — otherwise turning the feature on splits what it merges off.
        val segment = onlySegment(listOf(tool("t1"), label(""), tool("t2")))

        val group = segment.groups.single() as ContentGroup.Activity
        assertEquals(listOf(0, 2), group.entries.map { it.index })
        assertEquals("", group.labelText)
    }

    @Test
    fun aFilledLabelCannotReachBackPastABlankOne() {
        val segment = onlySegment(listOf(tool("t1"), label(""), tool("t2"), label("Ran the second batch")))

        assertEquals(2, segment.groups.size)
        assertTrue(segment.groups[0] is ContentGroup.Single, "the blank-labeled batch renders legacy-style")
        val labeled = segment.groups[1] as ContentGroup.Activity
        assertEquals(listOf(2), labeled.entries.map { it.index })
        assertEquals("Ran the second batch", labeled.labelText)
    }

    @Test
    fun anOrphanLabelRendersOnItsOwn() {
        val segment = onlySegment(listOf(text("hi"), label("Looked something up")))
        assertEquals(2, segment.groups.size)
        assertTrue(segment.groups[1] is ContentGroup.Single)
    }

    @Test
    fun anOrphanLabelOverAHandoffIsDropped() {
        // The handoff card already names the destination; the label would be a stray line.
        val parts = listOf(
            tool("h1", name = "lc_transfer_to_billing", output = null),
            label("Handing off", toolCallIds = listOf("h1")),
        )
        val segment = onlySegment(parts)
        assertTrue(segment.groups.none { g -> g.entries.any { it.part.type == ContentType.ACTIVITY_LABEL } })
    }

    @Test
    fun handoffCallsAreNeverFoldedIntoAGroup() {
        val segment = onlySegment(
            listOf(tool("h1", name = "lc_transfer_to_billing", output = null), tool("h2", name = "lc_transfer_to_sales", output = null)),
        )
        assertTrue(segment.groups.all { it is ContentGroup.Single })
    }

    @Test
    fun anyOtherPartTypeBreaksTheBlock() {
        val segment = onlySegment(listOf(tool("t1"), tool("t2"), text("interjection"), tool("t3"), tool("t4")))
        assertEquals(3, segment.groups.size)
        assertEquals(listOf(0, 1), (segment.groups[0] as ContentGroup.Activity).entries.map { it.index })
        assertTrue(segment.groups[1] is ContentGroup.Single)
        assertEquals(listOf(3, 4), (segment.groups[2] as ContentGroup.Activity).entries.map { it.index })
    }

    // ─── group identity and collapse ────────────────────────────────

    @Test
    fun groupKeyIsStableWhenTheLabelAbsorbsLeadingReasoning() {
        // The block's FIRST PART flips from the tool call to the THINK the moment the label lands.
        // Keying on it would remount the group and drop whatever the user had expanded.
        val beforeLabel = onlySegment(listOf(think("planning"), tool("t1"), tool("t2")))
        val afterLabel = onlySegment(listOf(think("planning"), tool("t1"), tool("t2"), label("Searched")))

        val before = beforeLabel.groups.filterIsInstance<ContentGroup.Activity>().single()
        val after = afterLabel.groups.filterIsInstance<ContentGroup.Activity>().single()
        assertEquals("tool:t1", before.key)
        assertEquals(before.key, after.key)
    }

    @Test
    fun groupKeyFallsBackToTheFirstToolIndexWhenCallsCarryNoId() {
        val parts = listOf(
            think("planning"),
            MessageContentPart(type = ContentType.TOOL_CALL, toolCall = AgentToolCall(name = "a", output = "x")),
            MessageContentPart(type = ContentType.TOOL_CALL, toolCall = AgentToolCall(name = "b", output = "y")),
            label("Did two things"),
        )
        assertEquals("toolidx:1", (onlySegment(parts).groups.single() as ContentGroup.Activity).key)
    }

    @Test
    fun aLabeledSingleCallCollapsesButAnUnlabeledPairNeedsToFinish() {
        val labeled = onlySegment(listOf(tool("t1"), label("Read the file")))
            .groups.single() as ContentGroup.Activity
        assertTrue(labeled.collapsedByDefault)

        val unlabeled = onlySegment(listOf(tool("t1"), tool("t2")))
            .groups.single() as ContentGroup.Activity
        assertTrue(unlabeled.collapsedByDefault)

        val unfinished = onlySegment(listOf(tool("t1", output = null), tool("t2", output = null)))
            .groups.single() as ContentGroup.Activity
        assertTrue(!unfinished.collapsedByDefault)
    }

    @Test
    fun aSettledLabelCountsAsCompletionEvenWhenAToolReturnedNothing() {
        // A tool legitimately returning "" would otherwise read as unfinished forever, and its
        // group would never collapse.
        val group = onlySegment(listOf(tool("t1", output = ""), label("Checked the config")))
            .groups.single() as ContentGroup.Activity
        assertTrue(group.completed)
    }

    @Test
    fun aPendingLabelIsNotCompletionProof() {
        val group = onlySegment(
            listOf(tool("t1", output = ""), tool("t2", output = ""), label("Working", pending = true)),
        ).groups.single() as ContentGroup.Activity
        assertTrue(!group.completed)
    }

    @Test
    fun aFailedBatchIsFlagged() {
        val group = onlySegment(listOf(tool("t1"), label("Tried to search", status = "partial")))
            .groups.single() as ContentGroup.Activity
        assertTrue(group.failed)
    }

    // Image-gen calls group like any other tool, matching `groupToolCalls.ts`. Generated images
    // stay visible because the render layer hoists them out of the collapsible, NOT because the
    // grouping skips them — do not add an image special-case here.
    @Test
    fun imageGenCallsStillGroupIntoOneActivityBlock() {
        val groups = onlySegment(
            listOf(tool("t1", name = "image_gen_oai"), tool("t2", name = "image_gen_oai")),
        ).groups

        val group = groups.single() as ContentGroup.Activity
        assertEquals(2, group.toolCount)
    }

    @Test
    fun groupedToolCallIdsReturnsToolCallsInOrderAndSkipsReasoning() {
        val group = onlySegment(listOf(tool("t1"), think("pondering"), tool("t2"), label("Looked it up")))
            .groups.single() as ContentGroup.Activity

        assertEquals(listOf("t1", "t2"), group.groupedToolCallIds())
    }

    @Test
    fun groupedToolCallIdsSkipsBlankIds() {
        val group = onlySegment(listOf(tool(""), tool("t2")))
            .groups.single() as ContentGroup.Activity

        assertEquals(listOf("t2"), group.groupedToolCallIds())
    }

    @Test
    fun groupedToolCallIdsIncludesCallsNestedInsideASubagent() {
        val group = onlySegment(
            listOf(
                subagent("s1", nested = listOf(tool("n1", name = "image_gen_oai"), tool("n2"))),
                tool("t2"),
            ),
        ).groups.single() as ContentGroup.Activity

        assertEquals(listOf("s1", "n1", "n2", "t2"), group.groupedToolCallIds())
    }

    // The walk is deliberately uncapped where the render stops at depth 1: a depth-2 subagent draws
    // no nested parts at all, but its files still have to reach the hoist.
    @Test
    fun groupedToolCallIdsDescendsThroughNestedSubagents() {
        val group = onlySegment(
            listOf(
                subagent("s1", nested = listOf(subagent("s2", nested = listOf(tool("n1"))))),
                label("Ran a subagent"),
            ),
        ).groups.single() as ContentGroup.Activity

        assertEquals(listOf("s1", "s2", "n1"), group.groupedToolCallIds())
    }

    // The ungrouped path: a lone subagent call never forms a group, and mid-run there is no
    // persisted `subagent_content` to walk — the live trace's parts are fed in directly.
    @Test
    fun outputToolCallIdsWalksALooseRunOfParts() {
        assertEquals(
            listOf("n1", "n2"),
            outputToolCallIds(listOf(think("pondering"), tool("n1"), text("done"), tool("n2"))),
        )
    }

    @Test
    fun outputToolCallIdsSkipsBlankAndNonToolParts() {
        assertEquals(listOf("n2"), outputToolCallIds(listOf(text("hi"), tool(""), tool("n2"))))
    }
}
