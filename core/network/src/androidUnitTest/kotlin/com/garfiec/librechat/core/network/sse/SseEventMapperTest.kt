package com.garfiec.librechat.core.network.sse

import com.garfiec.librechat.core.model.StreamEvent
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Before
import org.junit.Test

class SseEventMapperTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }
    private lateinit var mapper: SseEventMapper

    @Before
    fun setUp() {
        mapper = SseEventMapper(json)
    }

    // --- Content Delta (LangGraph) ---

    @Test
    fun `maps on_message_delta to ContentDelta`() {
        val event = SseEvent(
            event = "",
            data = """{"event":"on_message_delta","data":{"id":"step_1","delta":{"content":[{"type":"text","text":"Hello "}]}}}""",
        )
        val result = mapper.map(event)
        assertThat(result).isInstanceOf(StreamEvent.ContentDelta::class.java)
        val delta = result as StreamEvent.ContentDelta
        assertThat(delta.chunk).isEqualTo("Hello ")
        assertThat(delta.messageId).isEqualTo("step_1")
    }

    @Test
    fun `maps on_message_delta with multiple content parts`() {
        val event = SseEvent(
            event = "",
            data = """{"event":"on_message_delta","data":{"id":"step_1","delta":{"content":[{"type":"text","text":"Hello "},{"type":"text","text":"world"}]}}}""",
        )
        val result = mapper.map(event) as StreamEvent.ContentDelta
        assertThat(result.chunk).isEqualTo("Hello world")
    }

    @Test
    fun `returns null for empty content delta`() {
        val event = SseEvent(
            event = "",
            data = """{"event":"on_message_delta","data":{"id":"step_1","delta":{"content":[{"type":"image_url","url":"http://x.com"}]}}}""",
        )
        val result = mapper.map(event)
        assertThat(result).isNull()
    }

    // --- Thinking Delta ---

    @Test
    fun `maps on_reasoning_delta to ThinkingDelta`() {
        val event = SseEvent(
            event = "",
            data = """{"event":"on_reasoning_delta","data":{"id":"step_1","delta":{"content":[{"type":"think","think":"Let me consider..."}]}}}""",
        )
        val result = mapper.map(event)
        assertThat(result).isInstanceOf(StreamEvent.ThinkingDelta::class.java)
        assertThat((result as StreamEvent.ThinkingDelta).chunk).isEqualTo("Let me consider...")
    }

    // --- Tool Calls ---

    @Test
    fun `maps on_run_step to ToolCallStart`() {
        val event = SseEvent(
            event = "",
            data = """{"event":"on_run_step","data":{"id":"step_1","stepDetails":{"type":"tool_calls","tool_calls":[{"id":"call_1","name":"web_search","args":"{\"query\":\"kotlin\"}"}]},"agentId":"agent_1","groupId":1}}""",
        )
        val result = mapper.map(event)
        assertThat(result).isInstanceOf(StreamEvent.ToolCallStart::class.java)
        val tc = result as StreamEvent.ToolCallStart
        assertThat(tc.toolCallId).isEqualTo("call_1")
        assertThat(tc.toolName).isEqualTo("web_search")
        assertThat(tc.agentId).isEqualTo("agent_1")
        assertThat(tc.groupId).isEqualTo(1)
    }

    @Test
    fun `maps on_run_step_completed to ToolCallComplete`() {
        val event = SseEvent(
            event = "",
            data = """{"event":"on_run_step_completed","data":{"result":{"id":"step_1","tool_call":{"id":"call_1","name":"search","output":"Results here"}},"agentId":"agent_1","groupId":1}}""",
        )
        val result = mapper.map(event)
        assertThat(result).isInstanceOf(StreamEvent.ToolCallComplete::class.java)
        val tc = result as StreamEvent.ToolCallComplete
        assertThat(tc.toolCallId).isEqualTo("call_1")
        assertThat(tc.output).isEqualTo("Results here")
    }

    @Test
    fun `tool call output handles JSON object values`() {
        val event = SseEvent(
            event = "",
            data = """{"event":"on_run_step_completed","data":{"result":{"id":"s","tool_call":{"id":"c1","output":{"url":"http://img.com/x.png"}}}}}""",
        )
        val result = mapper.map(event) as StreamEvent.ToolCallComplete
        assertThat(result.output).contains("http://img.com/x.png")
    }

    // --- Agent Context Tracking ---

    @Test
    fun `tracks agentId from on_run_step and applies to subsequent content events`() {
        // Step 1: on_run_step sets agent context
        mapper.map(
            SseEvent(
                event = "",
                data = """{"event":"on_run_step","data":{"id":"s1","stepDetails":{"type":"tool_calls","tool_calls":[{"id":"c1","name":"search","args":""}]},"agentId":"agent_A","groupId":2}}""",
            ),
        )

        // Step 2: on_message_delta doesn't carry agentId — should inherit from step
        val delta = mapper.map(
            SseEvent(
                event = "",
                data = """{"event":"on_message_delta","data":{"id":"s2","delta":{"content":[{"type":"text","text":"Hi"}]}}}""",
            ),
        )

        assertThat(delta).isInstanceOf(StreamEvent.ContentDelta::class.java)
        assertThat((delta as StreamEvent.ContentDelta).agentId).isEqualTo("agent_A")
        assertThat(delta.groupId).isEqualTo(2)
    }

    @Test
    fun `resetState clears tracked agent context`() {
        mapper.map(
            SseEvent(
                event = "",
                data = """{"event":"on_run_step","data":{"id":"s1","stepDetails":{"type":"tool_calls","tool_calls":[{"id":"c1","name":"t","args":""}]},"agentId":"agent_A","groupId":1}}""",
            ),
        )

        mapper.resetState()

        val delta = mapper.map(
            SseEvent(
                event = "",
                data = """{"event":"on_message_delta","data":{"id":"s2","delta":{"content":[{"type":"text","text":"Hi"}]}}}""",
            ),
        ) as StreamEvent.ContentDelta

        assertThat(delta.agentId).isNull()
        assertThat(delta.groupId).isNull()
    }

    @Test
    fun `attributes interleaved parallel deltas to their own step id, not last run step`() {
        // Compare Models: two agents stream in parallel. Their run steps arrive
        // staggered and deltas interleave per-chunk. Each delta carries only its
        // own step id, so attribution must key on that id — not a shared
        // last-write-wins that would stamp both agents with whichever ran last.
        mapper.map(runStep(stepId = "s_added", agentId = "agent_x____1", groupId = 1))
        mapper.map(runStep(stepId = "s_primary", agentId = "agent_x", groupId = 0))

        val addedDelta = mapper.map(messageDelta(stepId = "s_added", text = "sky")) as StreamEvent.ContentDelta
        val primaryDelta = mapper.map(messageDelta(stepId = "s_primary", text = "blue")) as StreamEvent.ContentDelta

        assertThat(addedDelta.agentId).isEqualTo("agent_x____1")
        assertThat(addedDelta.groupId).isEqualTo(1)
        assertThat(primaryDelta.agentId).isEqualTo("agent_x")
        assertThat(primaryDelta.groupId).isEqualTo(0)
    }

    @Test
    fun `attributes reasoning deltas by their own step id in a parallel run`() {
        mapper.map(runStep(stepId = "s_added", agentId = "agent_x____1", groupId = 1))
        mapper.map(runStep(stepId = "s_primary", agentId = "agent_x", groupId = 0))

        val addedThink = mapper.map(
            SseEvent(
                event = "",
                data = """{"event":"on_reasoning_delta","data":{"id":"s_added","delta":{"content":[{"type":"think","think":"hmm"}]}}}""",
            ),
        ) as StreamEvent.ThinkingDelta

        assertThat(addedThink.agentId).isEqualTo("agent_x____1")
        assertThat(addedThink.groupId).isEqualTo(1)
    }

    @Test
    fun `on_run_step_completed resolves attribution from its result step id`() {
        mapper.map(runStep(stepId = "s_added", agentId = "agent_x____1", groupId = 1))
        mapper.map(runStep(stepId = "s_primary", agentId = "agent_x", groupId = 0))

        // Completion event carries no top-level agentId; resolve via result.id.
        val completed = mapper.map(
            SseEvent(
                event = "",
                data = """{"event":"on_run_step_completed","data":{"result":{"id":"s_added","tool_call":{"id":"c1","output":"done"}}}}""",
            ),
        ) as StreamEvent.ToolCallComplete

        assertThat(completed.agentId).isEqualTo("agent_x____1")
        assertThat(completed.groupId).isEqualTo(1)
    }

    @Test
    fun `resetState clears per-step attribution map`() {
        mapper.map(runStep(stepId = "s_added", agentId = "agent_x____1", groupId = 1))
        mapper.resetState()

        val delta = mapper.map(messageDelta(stepId = "s_added", text = "hi")) as StreamEvent.ContentDelta
        assertThat(delta.agentId).isNull()
        assertThat(delta.groupId).isNull()
    }

    private fun runStep(stepId: String, agentId: String, groupId: Int) = SseEvent(
        event = "",
        data = """{"event":"on_run_step","data":{"id":"$stepId","stepDetails":{"type":"tool_calls","tool_calls":[{"id":"c_$stepId","name":"t","args":""}]},"agentId":"$agentId","groupId":$groupId}}""",
    )

    private fun messageDelta(stepId: String, text: String) = SseEvent(
        event = "",
        data = """{"event":"on_message_delta","data":{"id":"$stepId","delta":{"content":[{"type":"text","text":"$text"}]}}}""",
    )

    // --- Control Events ---

    @Test
    fun `maps final event with full payload`() {
        val event = SseEvent(
            event = "",
            data = """{"final":true,"conversation":{"conversationId":"c1","title":"Chat"},"requestMessage":{"messageId":"m1","conversationId":"c1","text":"Hello","isCreatedByUser":true},"responseMessage":{"messageId":"m2","conversationId":"c1","text":"Hi there","isCreatedByUser":false}}""",
        )
        val result = mapper.map(event)
        assertThat(result).isInstanceOf(StreamEvent.Final::class.java)
        val final = result as StreamEvent.Final
        assertThat(final.conversation?.conversationId).isEqualTo("c1")
        assertThat(final.requestMessage?.text).isEqualTo("Hello")
        assertThat(final.responseMessage?.text).isEqualTo("Hi there")
        assertThat(final.hasParseErrors).isFalse()
    }

    @Test
    fun `maps final event with legacy message field`() {
        val event = SseEvent(
            event = "",
            data = """{"final":true,"message":{"messageId":"m1","conversationId":"c1","text":"Done"}}""",
        )
        val final = mapper.map(event) as StreamEvent.Final
        assertThat(final.message?.text).isEqualTo("Done")
    }

    @Test
    fun `maps created event with nested message object`() {
        val event = SseEvent(
            event = "",
            data = """{"created":{"message":{"conversationId":"c1","messageId":"m1","parentMessageId":"m0"}}}""",
        )
        val result = mapper.map(event) as StreamEvent.Created
        assertThat(result.conversationId).isEqualTo("c1")
        assertThat(result.messageId).isEqualTo("m1")
        assertThat(result.parentMessageId).isEqualTo("m0")
    }

    @Test
    fun `maps created event with boolean flag format`() {
        val event = SseEvent(
            event = "",
            data = """{"created":true,"message":{"conversationId":"c1","messageId":"m1","parentMessageId":"m0"}}""",
        )
        val result = mapper.map(event) as StreamEvent.Created
        assertThat(result.conversationId).isEqualTo("c1")
    }

    @Test
    fun `maps error event`() {
        val event = SseEvent(
            event = "",
            data = """{"error":"Rate limit exceeded"}""",
        )
        val result = mapper.map(event) as StreamEvent.Error
        assertThat(result.message).isEqualTo("Rate limit exceeded")
    }

    @Test
    fun `maps sync event with aggregatedContent`() {
        val event = SseEvent(
            event = "",
            data = """{"sync":true,"resumeState":{"aggregatedContent":[{"type":"text","text":"Previously streamed text"}]}}""",
        )
        val result = mapper.map(event) as StreamEvent.Sync
        assertThat(result.aggregatedContent).hasSize(1)
        assertThat(result.aggregatedContent[0].text).isEqualTo("Previously streamed text")
    }

    @Test
    fun `sync aggregatedContent carries in-progress and completed tool_call parts`() {
        val event = SseEvent(
            event = "",
            data = """{"sync":true,"resumeState":{"aggregatedContent":[
                {"type":"text","text":"Let me generate that now"},
                {"type":"tool_call","tool_call":{"type":"tool_call","id":"call_done","name":"web_search","args":"{}","output":"results"}},
                {"type":"tool_call","tool_call":{"type":"tool_call","id":"call_pending","name":"image_gen_oai","args":"{\"prompt\":\"a cat\"}"}}
            ]}}""",
        )
        val result = mapper.map(event) as StreamEvent.Sync
        val toolCalls = result.aggregatedContent.mapNotNull { it.toolCall }
        assertThat(toolCalls).hasSize(2)
        assertThat(toolCalls[0].id).isEqualTo("call_done")
        assertThat(toolCalls[0].output).isEqualTo("results")
        assertThat(toolCalls[1].id).isEqualTo("call_pending")
        assertThat(toolCalls[1].output).isNull() // in-progress: drives the live card
    }

    @Test
    fun `mapFrame expands sync frame into snapshot then buffered pendingEvents in order`() {
        val event = SseEvent(
            event = "",
            data = """{"sync":true,"resumeState":{"aggregatedContent":[{"type":"text","text":"hi"}]},"pendingEvents":[
                {"event":"on_message_delta","data":{"id":"s","delta":{"content":[{"type":"text","text":" there"}]}}},
                {"event":"on_run_step_completed","data":{"result":{"id":"s","tool_call":{"id":"call_pending","output":"http://img/x.png"}}}}
            ]}""",
        )
        val events = mapper.mapFrame(event)
        assertThat(events).hasSize(3)
        assertThat(events[0]).isInstanceOf(StreamEvent.Sync::class.java)
        assertThat(events[1]).isInstanceOf(StreamEvent.ContentDelta::class.java)
        assertThat((events[1] as StreamEvent.ContentDelta).chunk).isEqualTo(" there")
        assertThat(events[2]).isInstanceOf(StreamEvent.ToolCallComplete::class.java)
        assertThat((events[2] as StreamEvent.ToolCallComplete).toolCallId).isEqualTo("call_pending")
    }

    @Test
    fun `mapFrame returns single-element list for an ordinary frame`() {
        val event = SseEvent(
            event = "",
            data = """{"event":"on_message_delta","data":{"id":"s","delta":{"content":[{"type":"text","text":"x"}]}}}""",
        )
        assertThat(mapper.mapFrame(event)).hasSize(1)
    }

    // --- Attachment Events ---

    @Test
    fun `maps SSE-level attachment event`() {
        val event = SseEvent(
            event = "attachment",
            data = """{"file_id":"f1","filename":"image.png","type":"image/png","filepath":"/files/image.png","width":800,"height":600}""",
        )
        val result = mapper.map(event) as StreamEvent.AttachmentCreated
        assertThat(result.fileId).isEqualTo("f1")
        assertThat(result.filename).isEqualTo("image.png")
        assertThat(result.width).isEqualTo(800)
        assertThat(result.height).isEqualTo(600)
    }

    @Test
    fun `maps librechat attachment event variant`() {
        val event = SseEvent(
            event = "librechat:attachment",
            data = """{"file_id":"f2","filename":"doc.pdf","type":"application/pdf"}""",
        )
        val result = mapper.map(event) as StreamEvent.AttachmentCreated
        assertThat(result.fileId).isEqualTo("f2")
    }

    @Test
    fun `maps web_search attachment carrying sources without a file`() {
        // Web-search results arrive as an attachment with no file_id/filename — the sources
        // are nested under the `web_search` key (organic + topStories).
        val event = SseEvent(
            event = "attachment",
            data = """{"type":"web_search","toolCallId":"call_ws","messageId":"m1","web_search":{"turn":0,"organic":[{"link":"https://en.wikipedia.org/wiki/Photosynthesis","title":"Photosynthesis - Wikipedia"}],"topStories":[{"link":"https://en.wiktionary.org/wiki/photosynthesis","title":"photosynthesis - Wiktionary","source":"en.wiktionary.org"}]}}""",
        )
        val result = mapper.map(event) as StreamEvent.AttachmentCreated
        assertThat(result.type).isEqualTo("web_search")
        assertThat(result.toolCallId).isEqualTo("call_ws")
        assertThat(result.webSearch).isNotNull()
        assertThat(result.webSearch!!.organic).hasSize(1)
        assertThat(result.webSearch!!.organic!!.single().link)
            .isEqualTo("https://en.wikipedia.org/wiki/Photosynthesis")
        assertThat(result.webSearch!!.topStories!!.single().title).isEqualTo("photosynthesis - Wiktionary")
    }

    // --- Legacy Flat Format ---

    @Test
    fun `maps legacy content event`() {
        val event = SseEvent(
            event = "",
            data = """{"type":"content","text":"Legacy text","messageId":"m1"}""",
        )
        val result = mapper.map(event) as StreamEvent.ContentDelta
        assertThat(result.chunk).isEqualTo("Legacy text")
        assertThat(result.messageId).isEqualTo("m1")
    }

    @Test
    fun `maps legacy thinking event`() {
        val event = SseEvent(
            event = "",
            data = """{"type":"thinking","text":"Hmm...","metadata":{"agentId":"a1","groupId":1}}""",
        )
        val result = mapper.map(event) as StreamEvent.ThinkingDelta
        assertThat(result.chunk).isEqualTo("Hmm...")
        assertThat(result.agentId).isEqualTo("a1")
    }

    @Test
    fun `maps legacy tool_call_start event`() {
        val event = SseEvent(
            event = "",
            data = """{"type":"tool_call_start","toolCallId":"tc1","toolName":"calculator","input":"2+2"}""",
        )
        val result = mapper.map(event) as StreamEvent.ToolCallStart
        assertThat(result.toolCallId).isEqualTo("tc1")
        assertThat(result.toolName).isEqualTo("calculator")
        assertThat(result.input).isEqualTo("2+2")
    }

    @Test
    fun `maps legacy text-only fallback event`() {
        val event = SseEvent(
            event = "",
            data = """{"text":"Fallback content"}""",
        )
        val result = mapper.map(event) as StreamEvent.ContentDelta
        assertThat(result.chunk).isEqualTo("Fallback content")
    }

    // --- Edge Cases ---

    @Test
    fun `returns null for blank data`() {
        assertThat(mapper.map(SseEvent(event = "", data = ""))).isNull()
        assertThat(mapper.map(SseEvent(event = "", data = "  "))).isNull()
    }

    @Test
    fun `returns null for DONE marker`() {
        assertThat(mapper.map(SseEvent(event = "", data = "[DONE]"))).isNull()
    }

    @Test
    fun `returns Error for unparseable JSON`() {
        val result = mapper.map(SseEvent(event = "", data = "not json at all"))
        assertThat(result).isInstanceOf(StreamEvent.Error::class.java)
        assertThat((result as StreamEvent.Error).message).contains("Parse error")
    }

    @Test
    fun `ignores on_chat_model_end event`() {
        val event = SseEvent(
            event = "",
            data = """{"event":"on_chat_model_end","data":{"id":"step_1"}}""",
        )
        assertThat(mapper.map(event)).isNull()
    }

    @Test
    fun `ignores on_run_step_delta event`() {
        val event = SseEvent(
            event = "",
            data = """{"event":"on_run_step_delta","data":{"id":"step_1","delta":{"args":"partial"}}}""",
        )
        assertThat(mapper.map(event)).isNull()
    }

    // --- Subagent update (v0.8.6) ---

    @Test
    fun `maps on_subagent_update message_delta to SubagentUpdate with inner ContentDelta`() {
        val event = SseEvent(
            event = "",
            data = """{"event":"on_subagent_update","data":{"runId":"r1","subagentRunId":"sr1","parentToolCallId":"call_42","subagentType":"researcher","subagentAgentId":"agent_x","phase":"message_delta","label":"Researching","data":{"id":"step_1","delta":{"content":[{"type":"text","text":"Found it"}]}}}}""",
        )
        val result = mapper.map(event)
        assertThat(result).isInstanceOf(StreamEvent.SubagentUpdate::class.java)
        val update = result as StreamEvent.SubagentUpdate
        assertThat(update.phase).isEqualTo("message_delta")
        assertThat(update.parentToolCallId).isEqualTo("call_42")
        assertThat(update.subagentRunId).isEqualTo("sr1")
        assertThat(update.subagentType).isEqualTo("researcher")
        assertThat(update.subagentAgentId).isEqualTo("agent_x")
        assertThat(update.label).isEqualTo("Researching")
        assertThat(update.inner).isInstanceOf(StreamEvent.ContentDelta::class.java)
        assertThat((update.inner as StreamEvent.ContentDelta).chunk).isEqualTo("Found it")
    }

    @Test
    fun `maps on_subagent_update reasoning_delta to inner ThinkingDelta`() {
        val event = SseEvent(
            event = "",
            data = """{"event":"on_subagent_update","data":{"subagentRunId":"sr1","parentToolCallId":"call_1","phase":"reasoning_delta","data":{"id":"s","delta":{"content":[{"type":"think","think":"Hmm"}]}}}}""",
        )
        val update = mapper.map(event) as StreamEvent.SubagentUpdate
        assertThat(update.phase).isEqualTo("reasoning_delta")
        assertThat(update.inner).isInstanceOf(StreamEvent.ThinkingDelta::class.java)
        assertThat((update.inner as StreamEvent.ThinkingDelta).chunk).isEqualTo("Hmm")
    }

    @Test
    fun `maps on_subagent_update run_step to inner ToolCallStart`() {
        val event = SseEvent(
            event = "",
            data = """{"event":"on_subagent_update","data":{"subagentRunId":"sr1","parentToolCallId":"call_1","phase":"run_step","data":{"id":"s","stepDetails":{"type":"tool_calls","tool_calls":[{"id":"c9","name":"search","args":""}]}}}}""",
        )
        val update = mapper.map(event) as StreamEvent.SubagentUpdate
        assertThat(update.inner).isInstanceOf(StreamEvent.ToolCallStart::class.java)
        assertThat((update.inner as StreamEvent.ToolCallStart).toolName).isEqualTo("search")
    }

    @Test
    fun `maps on_subagent_update lifecycle phase to SubagentUpdate with null inner`() {
        val event = SseEvent(
            event = "",
            data = """{"event":"on_subagent_update","data":{"subagentRunId":"sr1","parentToolCallId":"call_1","subagentType":"writer","phase":"start","label":"Starting"}}""",
        )
        val update = mapper.map(event) as StreamEvent.SubagentUpdate
        assertThat(update.phase).isEqualTo("start")
        assertThat(update.label).isEqualTo("Starting")
        assertThat(update.inner).isNull()
    }

    @Test
    fun `subagent_content round-trips on a persisted subagent tool_call`() {
        // Reload path: the child trace is harvested onto the parent tool_call.
        val toolCallJson = """{"type":"tool_call","name":"subagent","id":"call_1","subagent_content":[{"type":"text","text":"child output"}]}"""
        val parsed = json.decodeFromString(
            com.garfiec.librechat.core.model.content.AgentToolCall.serializer(),
            toolCallJson,
        )
        assertThat(parsed.name).isEqualTo("subagent")
        assertThat(parsed.subagentContent).hasSize(1)
        assertThat(parsed.subagentContent!![0].text).isEqualTo("child output")
    }

    // --- Office-doc attachment preview lifecycle (v0.8.6) ---

    @Test
    fun `maps pending office-doc attachment carrying status`() {
        val event = SseEvent(
            event = "attachment",
            data = """{"file_id":"f1","filename":"report.docx","type":"application/vnd.librechat.docx-preview","status":"pending"}""",
        )
        val result = mapper.map(event)
        assertThat(result).isInstanceOf(StreamEvent.AttachmentCreated::class.java)
        val att = result as StreamEvent.AttachmentCreated
        assertThat(att.fileId).isEqualTo("f1")
        assertThat(att.type).isEqualTo("application/vnd.librechat.docx-preview")
        assertThat(att.status).isEqualTo("pending")
        assertThat(att.text).isNull()
    }

    @Test
    fun `maps ready office-doc attachment carrying text and textFormat`() {
        val event = SseEvent(
            event = "attachment",
            data = """{"file_id":"f1","filename":"report.docx","type":"application/vnd.librechat.docx-preview","status":"ready","text":"<html><body>hi</body></html>","textFormat":"html"}""",
        )
        val att = mapper.map(event) as StreamEvent.AttachmentCreated
        assertThat(att.status).isEqualTo("ready")
        assertThat(att.textFormat).isEqualTo("html")
        assertThat(att.text).isEqualTo("<html><body>hi</body></html>")
    }

    @Test
    fun `maps failed office-doc attachment carrying previewError`() {
        val event = SseEvent(
            event = "attachment",
            data = """{"file_id":"f1","filename":"report.docx","type":"application/vnd.librechat.docx-preview","status":"failed","previewError":"timeout"}""",
        )
        val att = mapper.map(event) as StreamEvent.AttachmentCreated
        assertThat(att.status).isEqualTo("failed")
        assertThat(att.previewError).isEqualTo("timeout")
    }
}
