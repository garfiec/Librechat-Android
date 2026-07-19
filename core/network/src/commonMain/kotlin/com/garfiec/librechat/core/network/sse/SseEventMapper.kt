package com.garfiec.librechat.core.network.sse

import co.touchlab.kermit.Logger
import com.garfiec.librechat.core.logging.Diag
import com.garfiec.librechat.core.logging.LogOrigin
import com.garfiec.librechat.core.model.Conversation
import com.garfiec.librechat.core.model.Message
import com.garfiec.librechat.core.model.StreamEvent
import com.garfiec.librechat.core.model.SubagentPhase
import com.garfiec.librechat.core.model.content.MessageContentPart
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Maps raw SSE events into domain [StreamEvent]s.
 *
 * Supports both the LangGraph nested event format (used by agents endpoint)
 * and the legacy flat format. The backend sends events in this structure:
 *
 * **LangGraph events** have an `"event"` key:
 * ```json
 * {"event":"on_message_delta","data":{"id":"step_xxx","delta":{"content":[{"type":"text","text":"Hello"}]}}}
 * ```
 *
 * **Control events** have top-level flag keys:
 * ```json
 * {"final":true,"conversation":{...},"requestMessage":{...},"responseMessage":{...}}
 * {"created":{"message":{...}}}
 * {"sync":true,"resumeState":{...}}
 * ```
 *
 * NOTE: This class holds mutable state that is reset per-connection via [resetState].
 * It is NOT thread-safe. Only use from a single coroutine (as done in [SseClient.connect]).
 */
class SseEventMapper(private val json: Json) {

    // Track agent context from on_run_step for cross-event correlation.
    // on_message_delta events don't carry agentId/groupId; the server only
    // includes them on on_run_step events. We store the last values and
    // apply them to subsequent content events.
    private var activeAgentId: String? = null
    private var activeGroupId: Int? = null

    // Per-step agent attribution (step id -> agentId/groupId). Deltas carry only
    // their step `id` (data["id"]), never an agentId. In a Compare Models run two
    // agents stream in parallel and their run steps interleave, so a single
    // last-write-wins activeAgentId mis-stamps every delta with whichever agent's
    // run step arrived most recently. We record each run step's attribution here
    // and resolve deltas by their own step id (mirrors the web client's stepMap
    // in useStepHandler.ts), keeping activeAgentId only as a fallback for events
    // whose step was never announced (e.g. some handoff/subagent frames).
    private val stepAgentContext = mutableMapOf<String, Pair<String?, Int?>>()

    /** Resets tracked state. Call when starting a new SSE stream. */
    fun resetState() {
        activeAgentId = null
        activeGroupId = null
        stepAgentContext.clear()
    }

    /**
     * Safely extracts a string from a [JsonElement] that may be a [JsonPrimitive] (string)
     * or a [JsonObject]/[JsonArray]. Tool call args and outputs from agent SSE streams
     * (e.g. image generation) arrive as JSON objects, not string primitives.
     * Returns [JsonPrimitive.contentOrNull] for primitives, or [JsonElement.toString]
     * (the raw JSON text) for objects and arrays.
     */
    private fun JsonElement?.toStringValue(): String? = when (this) {
        null -> null
        is JsonPrimitive -> contentOrNull
        else -> toString()
    }

    /**
     * Maps a single SSE frame to at-most-one [StreamEvent]. Retained for callers
     * (and tests) that only ever produce one event per frame. The streaming
     * pipeline uses [mapFrame] instead, because the resume `sync` frame expands
     * into several events; this delegate keeps only the first and would drop a
     * sync frame's buffered events — never route the live stream through it.
     */
    fun map(event: SseEvent): StreamEvent? = mapFrame(event).firstOrNull()

    /**
     * Maps a single SSE frame to the (possibly multiple) [StreamEvent]s it carries.
     *
     * Almost every frame yields one event. The exception is the resume `sync`
     * frame, which is a composite: a state snapshot ([StreamEvent.Sync] built from
     * `resumeState.aggregatedContent`) followed by `pendingEvents` — raw LangGraph
     * events that occurred after the snapshot and are delivered here exactly once
     * (the live continuation does not replay them). The pending events are mapped
     * through the same [mapJsonObject] the live stream uses, so downstream they are
     * indistinguishable from live events. List order is significant: the snapshot
     * must be applied before the buffered deltas that build on it.
     */
    fun mapFrame(event: SseEvent): List<StreamEvent> {
        if (event.data.isBlank() || event.data == "[DONE]") return emptyList()

        return try {
            val root = json.parseToJsonElement(event.data).jsonObject
            // Check SSE event type for attachment events sent as
            // `event: attachment\ndata: {...}` (the data object won't have
            // an "event" key — it's the raw attachment metadata).
            if (event.event == "attachment" || event.event == "librechat:attachment") {
                return listOfNotNull(mapAttachment(root))
            }
            // Resume sync frame: snapshot + buffered events. `pendingEvents` lives
            // at the frame top level (alongside `sync`/`resumeState`), not inside it.
            if (root["sync"]?.jsonPrimitive?.booleanOrNull == true) {
                val sync = mapSyncEvent(root)
                val pending = root["pendingEvents"]?.jsonArray.orEmpty()
                    .filterIsInstance<JsonObject>()
                    .mapNotNull(::mapJsonObject)
                return listOfNotNull(sync) + pending
            }
            listOfNotNull(mapJsonObject(root))
        } catch (e: Exception) {
            Diag.w(
                "SSE",
                origin = LogOrigin.CLIENT,
                throwable = e,
                attrs = mapOf("event" to event.event),
            ) { "SSE parse error" }
            listOf(StreamEvent.Error(message = "Parse error: ${e.message}"))
        }
    }

    private fun mapJsonObject(root: JsonObject): StreamEvent? {
        // 1. Check for "final" control event (highest priority)
        if (root["final"]?.jsonPrimitive?.booleanOrNull == true) {
            return mapFinalEvent(root)
        }

        // 2. Check for "created" control event
        if (root.containsKey("created")) {
            return mapCreatedEvent(root)
        }

        // The "sync" control event is handled in mapFrame (it expands to a snapshot
        // plus its buffered pendingEvents) before any frame reaches here, so there is
        // deliberately no sync branch in this per-event dispatcher.

        // 3. Check for "error" field (may be a string or an object)
        val errorText = root["error"]?.toStringValue()
        if (errorText != null) {
            return StreamEvent.Error(message = errorText)
        }

        // 4. Check for LangGraph nested event (has "event" key)
        val eventType = root["event"]?.jsonPrimitive?.contentOrNull
        if (eventType != null) {
            return mapLangGraphEvent(eventType, root)
        }

        // 5. Legacy flat format fallback
        return mapLegacyEvent(root)
    }

    // --- Control events ---

    private fun mapFinalEvent(root: JsonObject): StreamEvent {
        val parseErrors = mutableListOf<String>()

        val conversation = root["conversation"]?.let {
            try { json.decodeFromJsonElement(Conversation.serializer(), it) } catch (e: Exception) {
                val msg = "Failed to parse final conversation: ${e.message}"
                Logger.w("SSE", e) { msg }
                parseErrors.add(msg)
                null
            }
        }
        val requestMessage = root["requestMessage"]?.let {
            try { json.decodeFromJsonElement(Message.serializer(), it) } catch (e: Exception) {
                val msg = "Failed to parse final requestMessage: ${e.message}"
                Logger.w("SSE", e) { msg }
                parseErrors.add(msg)
                null
            }
        }
        val responseMessage = root["responseMessage"]?.let {
            try { json.decodeFromJsonElement(Message.serializer(), it) } catch (e: Exception) {
                val msg = "Failed to parse final responseMessage: ${e.message}"
                Logger.w("SSE", e) { msg }
                parseErrors.add(msg)
                null
            }
        }
        // Legacy: some events use "message" instead of "responseMessage"
        val legacyMessage = root["message"]?.let {
            try { json.decodeFromJsonElement(Message.serializer(), it) } catch (e: Exception) {
                val msg = "Failed to parse final legacy message: ${e.message}"
                Logger.w("SSE", e) { msg }
                parseErrors.add(msg)
                null
            }
        }

        // If ALL critical fields failed to parse, emit an Error instead
        val allFieldsNull = conversation == null && requestMessage == null &&
            responseMessage == null && legacyMessage == null
        if (allFieldsNull && parseErrors.isNotEmpty()) {
            Diag.e(
                "SSE",
                origin = LogOrigin.CLIENT,
                attrs = mapOf("event" to "final", "failedFields" to parseErrors.size.toString()),
            ) { "Final event: all fields failed to parse" }
            return StreamEvent.Error(
                message = "Failed to parse final event: ${parseErrors.joinToString("; ")}",
            )
        }

        return StreamEvent.Final(
            message = legacyMessage,
            conversation = conversation,
            requestMessage = requestMessage,
            responseMessage = responseMessage,
            parseErrors = parseErrors,
            // A stopped turn ends with a `final` frame carrying these flags rather than a
            // separate event type, so Stop reaches the chat layer through the normal stream.
            // Safe casts, not `.jsonPrimitive`: that accessor throws on a non-primitive, and a
            // malformed flag must not take down an otherwise-usable final event.
            aborted = (root["aborted"] as? JsonPrimitive)?.booleanOrNull == true,
            earlyAbort = (root["earlyAbort"] as? JsonPrimitive)?.booleanOrNull == true,
        )
    }

    private fun mapCreatedEvent(root: JsonObject): StreamEvent.Created {
        // "created" can be either a boolean or an object containing message info
        val createdObj = root["created"]
        val messageObj = when {
            // Format: {"created": {"message": {...}}}
            createdObj is JsonObject -> createdObj["message"]?.jsonObject
            // Format: {"created": true, "message": {...}}
            else -> root["message"]?.jsonObject
        }

        val conversationId = messageObj?.get("conversationId")?.jsonPrimitive?.contentOrNull ?: ""
        val messageId = messageObj?.get("messageId")?.jsonPrimitive?.contentOrNull ?: ""
        val parentMessageId = messageObj?.get("parentMessageId")?.jsonPrimitive?.contentOrNull ?: ""

        return StreamEvent.Created(
            conversationId = conversationId,
            messageId = messageId,
            parentMessageId = parentMessageId,
        )
    }

    private fun mapSyncEvent(root: JsonObject): StreamEvent? {
        val resumeState = root["resumeState"]?.jsonObject ?: return null

        // We intentionally do NOT replay `resumeState.runSteps`. The web client
        // replays them as on_run_step events to seed its event-sourced step-index
        // map; our client renders tool calls from a flat list keyed by id, and
        // `aggregatedContent` already carries every tool_call part (in-progress
        // with a null output, completed with its output merged in) in order — so
        // it is the authoritative snapshot. runSteps would only duplicate it.
        val aggregatedContent = resumeState["aggregatedContent"]?.jsonArray ?: return null

        val contentParts = aggregatedContent.mapNotNull { element ->
            try {
                json.decodeFromJsonElement(
                    MessageContentPart.serializer(),
                    element,
                )
            } catch (e: Exception) {
                Logger.w("SSE", e) { "Failed to parse sync aggregatedContent part" }
                null
            }
        }

        return StreamEvent.Sync(aggregatedContent = contentParts)
    }

    // --- LangGraph events ---

    private fun mapLangGraphEvent(eventType: String, root: JsonObject): StreamEvent? {
        val data = root["data"]?.jsonObject ?: return null
        val metadata = data["metadata"]?.jsonObject

        // Server puts agentId/groupId at top-level of data for on_run_step,
        // and optionally in data.metadata for other events.
        val eventAgentId = data["agentId"]?.jsonPrimitive?.contentOrNull
            ?: metadata?.get("agentId")?.jsonPrimitive?.contentOrNull
        val eventGroupId = data["groupId"]?.jsonPrimitive?.intOrNull
            ?: metadata?.get("groupId")?.jsonPrimitive?.intOrNull

        // Record each run step's attribution keyed by its step id, so deltas that
        // follow (and only carry that step id, never an agentId) can be resolved
        // to the right agent even when two agents' run steps interleave.
        val stepId = data["id"]?.jsonPrimitive?.contentOrNull
        if (eventType == "on_run_step" && stepId != null && (eventAgentId != null || eventGroupId != null)) {
            stepAgentContext[stepId] = eventAgentId to eventGroupId
        }

        // Track agent context from run steps as a last-resort fallback for events
        // whose step was never announced (e.g. some handoff frames).
        if (eventType == "on_run_step" && (eventAgentId != null || eventGroupId != null)) {
            activeAgentId = eventAgentId
            activeGroupId = eventGroupId
        }

        // Resolve attribution: explicit event-level values win; otherwise correlate
        // by this event's step id (deltas via data["id"], completions via result.id);
        // otherwise fall back to the most recent run step.
        val stepContext = stepId?.let { stepAgentContext[it] }
            ?: data["result"]?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull?.let { stepAgentContext[it] }
        val agentId = eventAgentId ?: stepContext?.first ?: activeAgentId
        val groupId = eventGroupId ?: stepContext?.second ?: activeGroupId

        return when (eventType) {
            "on_message_delta" -> mapMessageDelta(data, agentId, groupId)
            "on_reasoning_delta" -> mapReasoningDelta(data, agentId, groupId)
            "on_run_step" -> mapRunStep(data, agentId, groupId)
            "on_run_step_delta" -> null // Tool call argument streaming - not currently tracked
            "on_run_step_completed" -> mapRunStepCompleted(data, agentId, groupId)
            "on_chat_model_end" -> null
            "on_agent_update" -> null
            "on_summarize_start" -> null // Lifecycle only; no useful payload to surface
            "on_summarize_delta" -> null // Partial summary tokens; we only render the final summary
            "on_summarize_complete" -> mapSummarizeComplete(data, agentId, groupId)
            "on_subagent_update" -> mapSubagentUpdate(data, agentId, groupId)
            "attachment" -> mapAttachment(data)
            "title" -> mapTitleEvent(data)
            "on_token_usage" -> mapTokenUsage(data)
            "on_context_usage" -> mapContextUsage(data)
            else -> null // Forward-compat: unknown agent-library events drop silently.
        }
    }

    private fun mapSummarizeComplete(
        data: JsonObject,
        agentId: String?,
        groupId: Int?,
    ): StreamEvent? {
        // {"id":"...","agentId":"...","summary":{"type":"summary","content":[{"type":"text","text":"..."}],...}}
        val summary = data["summary"]?.jsonObject ?: return null
        val contentArray = summary["content"]?.jsonArray ?: return null

        val textBuilder = StringBuilder()
        for (element in contentArray) {
            val part = element.jsonObject
            val type = part["type"]?.jsonPrimitive?.contentOrNull
            if (type == "text") {
                val text = part["text"]?.jsonPrimitive?.contentOrNull
                if (text != null) textBuilder.append(text)
            }
        }

        val text = textBuilder.toString()
        if (text.isEmpty()) return null

        return StreamEvent.ContextSummary(
            summary = text,
            agentId = agentId ?: data["agentId"]?.jsonPrimitive?.contentOrNull,
            groupId = groupId,
        )
    }

    /**
     * Maps an `on_subagent_update` envelope (v0.8.6 subagents). The outer [data]
     * is a `SubagentUpdateEvent`; its `phase` selects how the nested `data`
     * payload is interpreted. That nested payload is structurally identical to
     * the matching top-level LangGraph event, so we reuse the existing extractors
     * (e.g. [mapMessageDelta], [mapReasoningDelta], [mapRunStep],
     * [mapRunStepCompleted]) to pre-map content phases into a flat [StreamEvent].
     * Lifecycle phases (`start`, `stop`, `error`) carry no inner event and only
     * advance the trace ticker. We don't propagate the subagent's own
     * agentId/groupId onto the inner event — the parent tool_call id is the
     * correlation key for rendering the trace.
     */
    private fun mapSubagentUpdate(
        data: JsonObject,
        agentId: String?,
        groupId: Int?,
    ): StreamEvent? {
        val phase = data["phase"]?.jsonPrimitive?.contentOrNull ?: return null
        val payload = data["data"]?.jsonObject
        val inner: StreamEvent? = if (payload == null) {
            null
        } else {
            when (phase) {
                SubagentPhase.MESSAGE_DELTA -> mapMessageDelta(payload, agentId, groupId)
                SubagentPhase.REASONING_DELTA -> mapReasoningDelta(payload, agentId, groupId)
                SubagentPhase.RUN_STEP -> mapRunStep(payload, agentId, groupId)
                SubagentPhase.RUN_STEP_COMPLETED -> mapRunStepCompleted(payload, agentId, groupId)
                else -> null // start / stop / error / run_step_delta carry no foldable content
            }
        }
        return StreamEvent.SubagentUpdate(
            phase = phase,
            parentToolCallId = data["parentToolCallId"]?.jsonPrimitive?.contentOrNull,
            subagentRunId = data["subagentRunId"]?.jsonPrimitive?.contentOrNull,
            subagentType = data["subagentType"]?.jsonPrimitive?.contentOrNull,
            subagentAgentId = data["subagentAgentId"]?.jsonPrimitive?.contentOrNull,
            label = data["label"]?.jsonPrimitive?.contentOrNull,
            inner = inner,
        )
    }

    private fun mapMessageDelta(
        data: JsonObject,
        agentId: String?,
        groupId: Int?,
    ): StreamEvent? {
        // {"id":"step_xxx","delta":{"content":[{"type":"text","text":"Hello"}]}}
        val delta = data["delta"]?.jsonObject ?: return null
        val contentArray = delta["content"]?.jsonArray ?: return null

        val textBuilder = StringBuilder()
        for (element in contentArray) {
            val part = element.jsonObject
            val type = part["type"]?.jsonPrimitive?.contentOrNull
            if (type == "text") {
                val text = part["text"]?.jsonPrimitive?.contentOrNull
                if (text != null) textBuilder.append(text)
            }
        }

        val text = textBuilder.toString()
        if (text.isEmpty()) return null

        return StreamEvent.ContentDelta(
            chunk = text,
            messageId = data["id"]?.jsonPrimitive?.contentOrNull,
            agentId = agentId,
            groupId = groupId,
        )
    }

    private fun mapReasoningDelta(
        data: JsonObject,
        agentId: String?,
        groupId: Int?,
    ): StreamEvent? {
        // {"id":"step_xxx","delta":{"content":[{"type":"think","think":"..."}]}}
        val delta = data["delta"]?.jsonObject ?: return null
        val contentArray = delta["content"]?.jsonArray ?: return null

        val thinkBuilder = StringBuilder()
        for (element in contentArray) {
            val part = element.jsonObject
            val type = part["type"]?.jsonPrimitive?.contentOrNull
            if (type == "think") {
                val think = part["think"]?.jsonPrimitive?.contentOrNull
                if (think != null) thinkBuilder.append(think)
            }
        }

        val text = thinkBuilder.toString()
        if (text.isEmpty()) return null

        return StreamEvent.ThinkingDelta(
            chunk = text,
            agentId = agentId,
            groupId = groupId,
        )
    }

    private fun mapRunStep(
        data: JsonObject,
        agentId: String?,
        groupId: Int?,
    ): StreamEvent? {
        // {"id":"step_xxx","stepDetails":{"type":"tool_calls","tool_calls":[{"id":"call_xxx","name":"search","args":""}]}}
        val stepDetails = data["stepDetails"]?.jsonObject ?: return null
        val toolCalls = stepDetails["tool_calls"]?.jsonArray ?: return null
        val firstToolCall = toolCalls.firstOrNull()?.jsonObject ?: return null

        val toolCallId = firstToolCall["id"]?.jsonPrimitive?.contentOrNull ?: return null
        val toolName = firstToolCall["name"]?.jsonPrimitive?.contentOrNull ?: ""
        val args = firstToolCall["args"]?.toStringValue() ?: ""

        return StreamEvent.ToolCallStart(
            toolCallId = toolCallId,
            toolName = toolName,
            input = args,
            agentId = agentId,
            groupId = groupId,
        )
    }

    private fun mapRunStepCompleted(
        data: JsonObject,
        agentId: String?,
        groupId: Int?,
    ): StreamEvent? {
        // {"result":{"id":"step_xxx","tool_call":{"id":"call_xxx","name":"search","output":"..."}}}
        val result = data["result"]?.jsonObject ?: return null
        val toolCall = result["tool_call"]?.jsonObject ?: return null

        val toolCallId = toolCall["id"]?.jsonPrimitive?.contentOrNull ?: return null
        val output = toolCall["output"]?.toStringValue() ?: ""

        return StreamEvent.ToolCallComplete(
            toolCallId = toolCallId,
            output = output,
            agentId = agentId,
            groupId = groupId,
        )
    }

    private fun mapTitleEvent(data: JsonObject): StreamEvent? {
        // {"conversationId":"...","title":"..."} — emitted mid-stream when
        // interface.titleTiming === 'immediate' (v0.8.7).
        val conversationId = data["conversationId"]?.jsonPrimitive?.contentOrNull ?: return null
        val title = data["title"]?.jsonPrimitive?.contentOrNull ?: return null
        if (title.isBlank()) return null
        return StreamEvent.TitleUpdate(conversationId = conversationId, title = title)
    }

    private fun mapTokenUsage(data: JsonObject): StreamEvent? {
        // data is a TTokenUsageEvent: {input_tokens, output_tokens, total_tokens, model, provider}.
        val usage = try {
            json.decodeFromJsonElement(com.garfiec.librechat.core.model.usage.TokenUsage.serializer(), data)
        } catch (e: Exception) {
            Logger.w("SSE", e) { "Failed to parse on_token_usage" }
            return null
        }
        return StreamEvent.TokenUsageUpdate(usage)
    }

    private fun mapContextUsage(data: JsonObject): StreamEvent? {
        // data is a TContextUsageEvent: {breakdown:{...}, contextBudget?, remainingContextTokens?, ...}.
        val usage = try {
            json.decodeFromJsonElement(com.garfiec.librechat.core.model.usage.ContextUsage.serializer(), data)
        } catch (e: Exception) {
            Logger.w("SSE", e) { "Failed to parse on_context_usage" }
            return null
        }
        return StreamEvent.ContextUsageUpdate(usage)
    }

    private fun mapAttachment(data: JsonObject): StreamEvent? {
        // Attachment events carry file metadata for tool-generated artifacts
        // (e.g., images from DALL-E / image_edit_oai). The data object has:
        //   file_id, filename, filepath, type, width, height, toolCallId, messageId, etc.
        val fileId = data["file_id"]?.jsonPrimitive?.contentOrNull ?: ""
        val filename = data["filename"]?.jsonPrimitive?.contentOrNull ?: ""
        val type = data["type"]?.jsonPrimitive?.contentOrNull ?: ""
        // Web-search results ride in as an attachment with no file — `type == "web_search"`
        // and the sources nested under the `web_search` key. Parse it before the file guard
        // so these aren't dropped as "empty" attachments.
        val webSearch = data["web_search"]?.let { element ->
            try {
                json.decodeFromJsonElement(com.garfiec.librechat.core.model.WebSearchData.serializer(), element)
            } catch (e: Exception) {
                Logger.w("SSE", e) { "Failed to parse web_search attachment data" }
                null
            }
        }
        if (fileId.isBlank() && filename.isBlank() && webSearch == null) return null
        return StreamEvent.AttachmentCreated(
            fileId = fileId,
            filename = filename,
            type = type,
            filepath = data["filepath"]?.jsonPrimitive?.contentOrNull,
            toolCallId = data["toolCallId"]?.jsonPrimitive?.contentOrNull,
            width = data["width"]?.jsonPrimitive?.intOrNull,
            height = data["height"]?.jsonPrimitive?.intOrNull,
            // Deferred office-doc preview lifecycle (v0.8.6). The same attachment is
            // emitted twice (pending → ready/failed); the chat layer upserts by file_id.
            status = data["status"]?.jsonPrimitive?.contentOrNull,
            text = data["text"]?.jsonPrimitive?.contentOrNull,
            textFormat = data["textFormat"]?.jsonPrimitive?.contentOrNull,
            previewError = data["previewError"]?.jsonPrimitive?.contentOrNull,
            webSearch = webSearch,
        )
    }

    // --- Legacy flat format fallback ---

    private fun mapLegacyEvent(root: JsonObject): StreamEvent? {
        val type = root["type"]?.jsonPrimitive?.contentOrNull
        val metadata = root["metadata"]?.jsonObject
        val agentId = metadata?.get("agentId")?.jsonPrimitive?.contentOrNull
        val groupId = metadata?.get("groupId")?.jsonPrimitive?.intOrNull

        return when (type) {
            "content", "text" -> {
                val text = root["text"]?.jsonPrimitive?.contentOrNull ?: return null
                StreamEvent.ContentDelta(
                    chunk = text,
                    messageId = root["messageId"]?.jsonPrimitive?.contentOrNull,
                    agentId = agentId,
                    groupId = groupId,
                )
            }
            "thinking", "think" -> {
                val text = root["text"]?.jsonPrimitive?.contentOrNull ?: return null
                StreamEvent.ThinkingDelta(
                    chunk = text,
                    agentId = agentId,
                    groupId = groupId,
                )
            }
            "tool_call_start" -> {
                StreamEvent.ToolCallStart(
                    toolCallId = root["toolCallId"]?.jsonPrimitive?.contentOrNull ?: "",
                    toolName = root["toolName"]?.jsonPrimitive?.contentOrNull ?: "",
                    input = root["input"]?.toStringValue() ?: "",
                    agentId = agentId,
                    groupId = groupId,
                )
            }
            "tool_call_complete" -> {
                StreamEvent.ToolCallComplete(
                    toolCallId = root["toolCallId"]?.jsonPrimitive?.contentOrNull ?: "",
                    output = root["output"]?.toStringValue() ?: "",
                    agentId = agentId,
                    groupId = groupId,
                )
            }
            else -> {
                // Check for message property (another legacy format)
                val text = root["text"]?.jsonPrimitive?.contentOrNull
                    ?: root["response"]?.jsonPrimitive?.contentOrNull
                if (text != null) {
                    StreamEvent.ContentDelta(
                        chunk = text,
                        agentId = agentId,
                        groupId = groupId,
                    )
                } else {
                    null
                }
            }
        }
    }
}
