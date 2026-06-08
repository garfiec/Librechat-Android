package com.garfiec.librechat.core.data.repository

import com.garfiec.librechat.core.model.request.ChatRequest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatPayloadBuilderTest {

    private val json = Json { encodeDefaults = false }

    @Test
    fun customEndpointWireFormatNeverFallsBackToAgents() {
        val req = ChatPayloadBuilder.build(
            text = "hello",
            conversationId = null,
            endpoint = "Deepseek",
            endpointType = "custom",
            key = "never",
            model = "deepseek-chat",
        )

        assertEquals("Deepseek", req.endpoint)
        assertEquals("custom", req.endpointType)
        assertEquals("never", req.key)

        val encoded = json.encodeToString(ChatRequest.serializer(), req)
        assertTrue("\"endpoint\":\"Deepseek\"" in encoded, "endpoint must be literal Deepseek")
        assertFalse("\"endpoint\":\"agents\"" in encoded, "endpoint must NOT fall back to agents")
    }

    @Test
    fun builtInUserProvidedEndpointSendsKey() {
        val req = ChatPayloadBuilder.build(
            text = "hi",
            conversationId = null,
            endpoint = "openAI",
            endpointType = "openAI",
            key = "never",
            model = "gpt-4o",
        )
        assertEquals("openAI", req.endpoint)
        assertEquals("openAI", req.endpointType)
        assertEquals("never", req.key)
    }

    @Test
    fun temporaryChatFlagOmittedByDefault() {
        val req = ChatPayloadBuilder.build(
            text = "hi",
            conversationId = null,
            endpoint = "openAI",
            model = "gpt-4o",
        )
        assertNull(req.isTemporary)
        val encoded = json.encodeToString(ChatRequest.serializer(), req)
        assertFalse("\"isTemporary\"" in encoded, "isTemporary must be omitted when not temporary")
    }

    @Test
    fun temporaryChatFlagSentWhenEnabled() {
        val req = ChatPayloadBuilder.build(
            text = "hi",
            conversationId = null,
            endpoint = "openAI",
            model = "gpt-4o",
            isTemporary = true,
        )
        assertEquals(true, req.isTemporary)
        val encoded = json.encodeToString(ChatRequest.serializer(), req)
        assertTrue("\"isTemporary\":true" in encoded, "isTemporary must serialize true when set")
    }

    @Test
    fun builtInEnvKeyEndpointOmitsKey() {
        val req = ChatPayloadBuilder.build(
            text = "hi",
            conversationId = null,
            endpoint = "anthropic",
            endpointType = "anthropic",
            key = null,
            model = "claude-sonnet-4-6",
        )
        assertEquals("anthropic", req.endpoint)
        assertNull(req.key)

        val encoded = json.encodeToString(ChatRequest.serializer(), req)
        assertFalse("\"key\"" in encoded, "key must be omitted (not encoded as null) when null")
    }

    @Test
    fun userMessageIdSerializedAsTopLevelMessageId() {
        val req = ChatPayloadBuilder.build(
            text = "hi",
            conversationId = null,
            endpoint = "openAI",
            model = "gpt-4o",
            userMessageId = "client-minted-123",
        )
        assertEquals("client-minted-123", req.messageId)
        val encoded = json.encodeToString(ChatRequest.serializer(), req)
        assertTrue("\"messageId\":\"client-minted-123\"" in encoded, "messageId must serialize when set")
    }

    @Test
    fun messageIdOmittedWhenNotProvided() {
        val req = ChatPayloadBuilder.build(
            text = "hi",
            conversationId = null,
            endpoint = "openAI",
            model = "gpt-4o",
        )
        assertNull(req.messageId)
        val encoded = json.encodeToString(ChatRequest.serializer(), req)
        assertFalse("\"messageId\"" in encoded, "messageId must be omitted when not provided (regenerate/continue)")
    }
}
