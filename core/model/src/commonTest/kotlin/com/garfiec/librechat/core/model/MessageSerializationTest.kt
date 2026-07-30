package com.garfiec.librechat.core.model

import com.garfiec.librechat.core.model.content.MessageContentPart
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class MessageSerializationTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun minimalMessageRoundTrip() {
        val original = Message(
            messageId = "msg-001",
            conversationId = "conv-001",
            text = "Hello, world!",
        )
        val encoded = json.encodeToString(Message.serializer(), original)
        val decoded = json.decodeFromString(Message.serializer(), encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun fullyPopulatedMessageRoundTrip() {
        val original = Message(
            messageId = "msg-002",
            conversationId = "conv-002",
            parentMessageId = "msg-001",
            responseMessageId = "msg-003",
            overrideParentMessageId = "msg-000",
            user = "user-123",
            model = "gpt-4o",
            endpoint = "openAI",
            sender = "GPT-4o",
            text = "Here is your answer.",
            isCreatedByUser = false,
            error = false,
            unfinished = false,
            finishReason = "stop",
            tokenCount = 42,
            iconURL = "https://example.com/icon.png",
            content = listOf(
                MessageContentPart(type = ContentType.TEXT, text = "Here is your answer."),
            ),
            files = listOf(
                FileReference(fileId = "file-1", filename = "data.csv", type = "text/csv"),
            ),
            attachments = listOf(
                Attachment(fileId = "att-1", filename = "image.png", type = "image/png"),
            ),
            feedback = Feedback(
                rating = FeedbackRating.THUMBS_UP,
                tag = JsonPrimitive("helpful"),
                text = "Great answer!",
            ),
            threadId = "thread-abc",
            metadata = JsonObject(mapOf("key" to JsonPrimitive("value"))),
            createdAt = "2026-03-28T12:00:00Z",
            updatedAt = "2026-03-28T12:01:00Z",
            title = "Test message",
        )
        val encoded = json.encodeToString(Message.serializer(), original)
        val decoded = json.decodeFromString(Message.serializer(), encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun messageWithNullOptionalFieldsRoundTrip() {
        val original = Message(
            messageId = "msg-003",
            conversationId = "conv-003",
            parentMessageId = null,
            model = null,
            content = null,
            files = null,
            metadata = null,
        )
        val encoded = json.encodeToString(Message.serializer(), original)
        val decoded = json.decodeFromString(Message.serializer(), encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun messageSkillFieldsRoundTrip() {
        // v0.8.6 added manualSkills / alwaysAppliedSkills; ensure both survive a round-trip
        // so the per-turn skill attribution isn't dropped on reload.
        val original = Message(
            messageId = "msg-skills",
            conversationId = "conv-skills",
            text = "Used some skills.",
            manualSkills = listOf("skill-a", "skill-b"),
            alwaysAppliedSkills = listOf("skill-c"),
        )
        val encoded = json.encodeToString(Message.serializer(), original)
        val decoded = json.decodeFromString(Message.serializer(), encoded)
        assertEquals(listOf("skill-a", "skill-b"), decoded.manualSkills)
        assertEquals(listOf("skill-c"), decoded.alwaysAppliedSkills)
        assertEquals(original, decoded)
    }

    @Test
    fun messageSkillFieldsDeserializeFromServerJson() {
        val serverJson = """
            {
                "messageId": "msg-srv-skills",
                "conversationId": "conv-srv-skills",
                "text": "Server response with skills",
                "manualSkills": ["s1", "s2"],
                "alwaysAppliedSkills": ["s3"]
            }
        """.trimIndent()
        val decoded = json.decodeFromString(Message.serializer(), serverJson)
        assertEquals(listOf("s1", "s2"), decoded.manualSkills)
        assertEquals(listOf("s3"), decoded.alwaysAppliedSkills)
    }

    @Test
    fun messageSkillFieldsDefaultNullWhenAbsent() {
        // Servers older than v0.8.6 omit the fields entirely — must decode to null, not throw.
        val serverJson = """
            {
                "messageId": "msg-no-skills",
                "conversationId": "conv-no-skills",
                "text": "No skills key present"
            }
        """.trimIndent()
        val decoded = json.decodeFromString(Message.serializer(), serverJson)
        assertEquals(null, decoded.manualSkills)
        assertEquals(null, decoded.alwaysAppliedSkills)
    }

    @Test
    fun messageWithActivityLabelPartDeserializes() {
        // v0.8.8 activity groups (upstream #14391) persist an `activity_label` part into saved
        // message content. `ContentType` has no default, so an undeclared value fails the whole
        // decode and takes every surrounding part with it — the conversation stops opening.
        val serverJson = """
            {
                "messageId": "msg-activity",
                "conversationId": "conv-activity",
                "text": "",
                "content": [
                    {"type": "text", "text": "Before the group."},
                    {
                        "type": "activity_label",
                        "activity_label": "Searched the codebase",
                        "tool_call_ids": ["call-1", "call-2"],
                        "counts": {"searches": 2, "reads": 1, "writes": 0, "commands": 0, "other": 0},
                        "status": "ok",
                        "agentId": "agent-1",
                        "pending": false
                    },
                    {"type": "text", "text": "After the group."}
                ]
            }
        """.trimIndent()
        val decoded = json.decodeFromString(Message.serializer(), serverJson)
        val parts = decoded.content!!
        assertEquals(3, parts.size)
        assertEquals("Before the group.", parts[0].text)
        assertEquals(ContentType.ACTIVITY_LABEL, parts[1].type)
        assertEquals("Searched the codebase", parts[1].activityLabel)
        assertEquals(listOf("call-1", "call-2"), parts[1].toolCallIds)
        assertEquals("agent-1", parts[1].agentId)
        assertEquals("After the group.", parts[2].text)
    }

    @Test
    fun messageWithPendingActivityLabelPartDeserializes() {
        // The reservation emitted at the tool-batch boundary, before the label model answers:
        // an empty label plus `pending: true`. Must decode as readily as the resolved form.
        val serverJson = """
            {
                "messageId": "msg-activity-pending",
                "conversationId": "conv-activity",
                "text": "",
                "content": [
                    {"type": "activity_label", "activity_label": "", "pending": true},
                    {"type": "text", "text": "Still streaming."}
                ]
            }
        """.trimIndent()
        val decoded = json.decodeFromString(Message.serializer(), serverJson)
        val parts = decoded.content!!
        assertEquals(2, parts.size)
        assertEquals(ContentType.ACTIVITY_LABEL, parts[0].type)
        assertEquals("", parts[0].activityLabel)
        assertEquals("Still streaming.", parts[1].text)
    }

    @Test
    fun steerPartSurvivesItsNumericCreatedAt() {
        // The server stamps a steer part's `createdAt` as epoch millis — a NUMBER — while the
        // field is typed String? for every other part that carries an ISO timestamp. Only
        // `isLenient` (set on the real client Json) keeps that from throwing, and a throw here
        // fails the whole `GET /messages` decode and loses every message in the conversation.
        // Pinned because the rescue is a Json setting, not anything visible at the declaration.
        val lenient = Json { ignoreUnknownKeys = true; isLenient = true }
        val serverJson = """
            {
                "messageId": "msg-steer",
                "conversationId": "conv-steer",
                "text": "",
                "content": [
                    {"type": "text", "text": "Working on it."},
                    {"type": "steer", "steer": "actually use Kotlin", "createdAt": 1753900000000},
                    {"type": "text", "text": "Switching to Kotlin."}
                ]
            }
        """.trimIndent()
        val parts = lenient.decodeFromString(Message.serializer(), serverJson).content!!
        assertEquals(3, parts.size)
        assertEquals(ContentType.STEER, parts[1].type)
        assertEquals("actually use Kotlin", parts[1].steer?.jsonPrimitive?.content)
        assertEquals("Switching to Kotlin.", parts[2].text)
    }

    @Test
    fun messageDeserializesFromServerJson() {
        val serverJson = """
            {
                "messageId": "msg-srv",
                "conversationId": "conv-srv",
                "text": "Server response",
                "isCreatedByUser": false,
                "sender": "Assistant",
                "finish_reason": "stop",
                "thread_id": "t-1",
                "unknownField": "should be ignored"
            }
        """.trimIndent()
        val decoded = json.decodeFromString(Message.serializer(), serverJson)
        assertEquals("msg-srv", decoded.messageId)
        assertEquals("stop", decoded.finishReason)
        assertEquals("t-1", decoded.threadId)
    }
}
