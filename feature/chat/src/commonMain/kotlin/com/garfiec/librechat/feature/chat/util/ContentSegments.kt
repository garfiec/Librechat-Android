package com.garfiec.librechat.feature.chat.util

import com.garfiec.librechat.core.common.ToolConstants
import com.garfiec.librechat.core.model.ContentType
import com.garfiec.librechat.core.model.content.MessageContentPart
import kotlinx.serialization.json.JsonPrimitive

/**
 * Turns a message's flat content-part list into the shape the bubble renders: segments split at
 * mid-run steers, each holding activity groups and standalone parts.
 *
 * ONE transform for both concerns on purpose. Activity grouping and steer segmentation both carve
 * up the same list, and two passes would eventually disagree about a boundary — a group spanning a
 * steer would render the user's words inside a collapsed tool block. Pure and memoizable so the
 * boundaries can be asserted directly; this module has no Compose test harness, so a function is
 * the only testable form.
 *
 * Mirrors upstream `client/src/utils/groupToolCalls.ts` (grouping) and the `postSteerAuthors` pass
 * in `ContentParts.tsx` (segmentation).
 *
 * @param groupActivity false for a comparison pane's lane, which renders every part standalone —
 *  see [hasParallelGroupIds]. Steer segmentation still applies either way.
 */
fun groupContentParts(
    parts: List<MessageContentPart>,
    groupActivity: Boolean = true,
): List<ContentSegment> {
    if (parts.isEmpty()) return emptyList()

    val segments = mutableListOf<ContentSegment>()
    var pending = mutableListOf<IndexedContentPart>()
    var pendingAuthor: SegmentAuthor? = null
    var previousType: ContentType? = null
    var activeAgentId: String? = null

    fun flushSegment(author: SegmentAuthor?) {
        if (pending.isEmpty()) return
        segments += ContentSegment(
            key = "seg:${pending.first().index}",
            author = author,
            groups = groupSequentially(pending, parts, groupActivity),
        )
        pending = mutableListOf()
    }

    parts.forEachIndexed { index, part ->
        // A steer renders as a full user turn inside the response, so whatever resumes after it
        // has to be re-attributed. The author is read BEFORE this part's own handoff is applied:
        // if the resume point IS an agent update, the pre-handoff author stands and the update
        // announces the transition itself.
        if (previousType == ContentType.STEER && part.type != ContentType.STEER) {
            flushSegment(pendingAuthor)
            pendingAuthor = activeAgentId?.let { SegmentAuthor.Agent(it) } ?: SegmentAuthor.Message
        }
        if (part.type == ContentType.AGENT_UPDATE) {
            activeAgentId = part.agentUpdate?.agentId?.takeIf { it.isNotBlank() }
        }
        previousType = part.type
        pending += IndexedContentPart(index = index, part = part)
    }
    flushSegment(pendingAuthor)

    return segments
}

/** A content part paired with its index in the message's `content` array. */
data class IndexedContentPart(val index: Int, val part: MessageContentPart)

/** Who the content after a segment boundary belongs to. */
sealed interface SegmentAuthor {
    /** Restate the message's own author — the steer interrupted, the same agent resumed. */
    data object Message : SegmentAuthor

    /** Restate a specific agent: the run had already handed off when the steer landed. */
    data class Agent(val agentId: String) : SegmentAuthor
}

/**
 * A run of content under one author. The first segment's [author] is null — the bubble's own
 * header already names it; every later segment restates attribution above its content.
 */
data class ContentSegment(
    val key: String,
    val author: SegmentAuthor?,
    val groups: List<ContentGroup>,
)

/** One rendered unit within a segment. */
sealed interface ContentGroup {
    /** Stable across the label arriving — see [activityGroupKey]. */
    val key: String
    val entries: List<IndexedContentPart>

    /** A part that renders on its own. */
    data class Single(val entry: IndexedContentPart) : ContentGroup {
        override val key: String = "part:${entry.index}"
        override val entries: List<IndexedContentPart> = listOf(entry)
    }

    /**
     * A reasoning + tool-call block rendered under one collapsible header. Labeled blocks carry
     * the model's own summary; unlabeled ones are the legacy run-of-two-or-more-tools grouping.
     */
    data class Activity(
        override val key: String,
        override val entries: List<IndexedContentPart>,
        /** The model-written summary, or empty for an unlabeled block. */
        val labelText: String,
        /** The batch reported `failed` or `partial`. */
        val failed: Boolean,
        /** Tool calls in the block. Absorbed reasoning parts are not counted. */
        val toolCount: Int,
        /** Every tool returned, or the label settled (which only happens after they all do). */
        val completed: Boolean,
    ) : ContentGroup {
        /**
         * A labeled block collapses even at a single call — agent runs are full of one-call
         * batches and leaving those open defeats the grouping. Unlabeled blocks keep the legacy
         * two-call threshold.
         */
        val collapsedByDefault: Boolean
            get() = completed && (toolCount >= 2 || labelText.isNotEmpty())
    }
}

/**
 * The ids of the tool calls whose output this block owns, in render order.
 *
 * Keep the attachment join at the render site rather than folding it into [ContentGroup]: that
 * would widen the grouping memo's key to include attachments and re-run the whole segmentation
 * pass on every mid-stream `attachment` event.
 */
fun ContentGroup.Activity.groupedToolCallIds(): List<String> =
    outputToolCallIds(entries.map { it.part })

/**
 * Every tool-call id whose output belongs to [parts]: each call's own id, then the ids of the calls
 * it ran nested inside it (`subagent_content`), recursively. Blank ids and non-tool parts drop out.
 *
 * **Load-bearing that this descends.** A subagent draws its nested parts with `hideAttachments`, so
 * this walk is the only thing that puts their files on screen. Narrow it back to the top level and
 * nested output renders nowhere.
 *
 * **DELIBERATE DIVERGENCE — a sync must NOT "correct" this toward upstream.** Upstream builds a
 * group's attachment set from `group.parts` alone (`ContentParts.tsx:365-369`) and hands its
 * subagent dialog no attachments, so a file generated *inside* a subagent is surfaced nowhere on
 * web. Restoring that parity reads as a harmless narrowing and silently takes the files off screen.
 */
fun outputToolCallIds(parts: List<MessageContentPart>): List<String> {
    val ids = mutableListOf<String>()
    fun walk(list: List<MessageContentPart>) {
        list.forEach { part ->
            val call = part.toolCall ?: return@forEach
            call.id?.takeIf(String::isNotEmpty)?.let(ids::add)
            call.subagentContent?.let(::walk)
        }
    }
    walk(parts)
    return ids
}

/** The label text an activity-label part carries, trimmed; empty when it is still a reservation. */
fun MessageContentPart.activityLabelText(): String =
    if (type == ContentType.ACTIVITY_LABEL) activityLabel?.trim().orEmpty() else ""

/**
 * The user's words from a steer part.
 *
 * Safe-cast rather than a typed `String` field: the payload is parked as a raw element so an
 * unexpected shape cannot fail the whole message decode, and upstream's own renderer guards
 * `typeof steer !== 'string'` for the same reason.
 */
fun MessageContentPart.steerText(): String? =
    (steer as? JsonPrimitive)
        ?.takeIf { it.isString }
        ?.content
        ?.takeIf { it.isNotBlank() }

private fun groupSequentially(
    entries: List<IndexedContentPart>,
    allParts: List<MessageContentPart>,
    groupActivity: Boolean,
): List<ContentGroup> {
    // A parallel (Compare Models) lane renders raw parts, as upstream's ParallelContentRenderer
    // does: no Activity group, so nothing hoists a part's attachments away from it.
    if (!groupActivity) return entries.map { ContentGroup.Single(it) }
    val result = mutableListOf<ContentGroup>()
    var block = mutableListOf<IndexedContentPart>()
    // Position in `block` just past the most recent blank label. A filled label may only claim
    // parts after it — its own batch. Anything earlier belongs to batches whose labels stayed
    // empty, and reaching back would relabel them.
    var claimStart = 0

    fun flushUnlabeled() {
        var run = mutableListOf<IndexedContentPart>()
        fun flushRun() {
            when {
                run.size >= 2 -> result += activityGroup(run.toList(), label = null)
                else -> run.forEach { result += ContentGroup.Single(it) }
            }
            run = mutableListOf()
        }
        block.forEach { entry ->
            if (entry.part.isGroupableToolCall()) {
                run += entry
            } else {
                flushRun()
                result += ContentGroup.Single(entry)
            }
        }
        flushRun()
        block = mutableListOf()
        claimStart = 0
    }

    entries.forEach { entry ->
        val part = entry.part
        if (part.isGroupableToolCall() || part.type == ContentType.THINK) {
            block += entry
            return@forEach
        }
        if (part.type == ContentType.ACTIVITY_LABEL) {
            if (part.activityLabelText().isEmpty()) {
                // A reserved-but-unfilled slot is INVISIBLE. Every batch publishes its
                // reservation immediately, so forming a group here would wrap a single tool call
                // and swallow reasoning while the label is still being written. Flushing is just
                // as wrong: two consecutive one-call batches would then render as two standalone
                // cards where the feature-off path merges them. It only moves the claim boundary.
                claimStart = block.size
                return@forEach
            }
            val claimed = block.drop(claimStart)
            // Earlier blank-labeled batches render legacy-style, in order, ahead of this group.
            block = block.take(claimStart).toMutableList()
            flushUnlabeled()
            when {
                claimed.isNotEmpty() -> result += activityGroup(claimed, label = part)
                // An orphan label (its parts were filtered out) renders as a bare line — unless
                // its batch held a handoff, whose card already names the destination and would
                // leave the label as a stray sentence under it.
                !part.coversTransferCall(allParts) -> result += ContentGroup.Single(entry)
            }
            return@forEach
        }
        flushUnlabeled()
        result += ContentGroup.Single(entry)
    }
    flushUnlabeled()

    return result
}

private fun activityGroup(entries: List<IndexedContentPart>, label: MessageContentPart?): ContentGroup.Activity {
    val labelText = label?.activityLabelText().orEmpty()
    val tools = entries.filter { it.part.type == ContentType.TOOL_CALL }
    // A settled, filled label is itself proof the batch finished — the server only claims it once
    // every output returned. Without that, a tool legitimately returning "" reads as unfinished
    // forever and its group never collapses.
    val labelSettled = labelText.isNotEmpty() && label?.pending != true
    return ContentGroup.Activity(
        key = activityGroupKey(entries),
        entries = entries,
        labelText = labelText,
        failed = label?.status == "failed" || label?.status == "partial",
        toolCount = tools.size,
        completed = labelSettled || tools.all { it.part.toolCall?.output?.isNotEmpty() == true },
    )
}

/**
 * Identity for a group's expansion state.
 *
 * Anchored to the first TOOL CALL, not the first part. A label absorbs the block's leading
 * reasoning when its text lands, so the block's first part flips from a tool call to a THINK at
 * the exact moment the thing becomes a labeled group — keying on it would reset whatever the user
 * had expanded. The tool calls themselves never move. Indices are the last resort, and only for a
 * block that has no tool call at all.
 */
private fun activityGroupKey(entries: List<IndexedContentPart>): String {
    entries.firstNotNullOfOrNull { it.part.toolCall?.id?.takeIf(String::isNotEmpty) }
        ?.let { return "tool:$it" }
    entries.firstOrNull { it.part.type == ContentType.TOOL_CALL }
        ?.let { return "toolidx:${it.index}" }
    return "block:${entries.first().index}"
}

private fun MessageContentPart.isGroupableToolCall(): Boolean {
    if (type != ContentType.TOOL_CALL) return false
    val call = toolCall ?: return false
    val name = call.name ?: call.function?.name
    // Handoffs never fold into a group: their card names the destination, and a batch label
    // heading one would say less than the card underneath it.
    return name?.startsWith(ToolConstants.LC_TRANSFER_TO_PREFIX) != true
}

/** True when this label covers any `transfer_to_*` call, which is never groupable. */
private fun MessageContentPart.coversTransferCall(allParts: List<MessageContentPart>): Boolean {
    val ids = toolCallIds?.takeIf { it.isNotEmpty() } ?: return false
    return allParts.any { candidate ->
        val call = candidate.toolCall ?: return@any false
        val name = call.name ?: call.function?.name
        call.id in ids && name?.startsWith(ToolConstants.LC_TRANSFER_TO_PREFIX) == true
    }
}
