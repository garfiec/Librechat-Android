package com.garfiec.librechat.core.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class ConversationSerializationTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun minimalConversationRoundTrip() {
        val original = Conversation(
            conversationId = "conv-001",
            title = "Test Chat",
        )
        val encoded = json.encodeToString(Conversation.serializer(), original)
        val decoded = json.decodeFromString(Conversation.serializer(), encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun fullyPopulatedConversationRoundTrip() {
        val original = Conversation(
            conversationId = "conv-002",
            title = "Full Chat",
            user = "user-123",
            endpoint = "openAI",
            endpointType = "agents",
            model = "gpt-4o",
            agentId = "agent-abc",
            assistantId = "asst-def",
            tags = listOf("work", "important"),
            isArchived = true,
            temperature = 0.7,
            topP = 0.9,
            topK = 40,
            frequencyPenalty = 0.5,
            presencePenalty = 0.3,
            maxOutputTokens = 4096,
            maxContextTokens = 128000,
            maxTokens = 4096,
            system = "You are a helpful assistant.",
            reasoningEffort = "high",
            effort = "high",
            thinkingLevel = "extended",
            stop = listOf("\n\n", "END"),
            iconURL = "https://example.com/icon.png",
            greeting = "Hello!",
            spec = "openAI",
            tools = listOf("web_search", "code_interpreter"),
            webSearch = true,
            createdAt = Instant.parse("2026-03-28T10:00:00Z"),
            updatedAt = Instant.parse("2026-03-28T11:00:00Z"),
        )
        val encoded = json.encodeToString(Conversation.serializer(), original)
        val decoded = json.decodeFromString(Conversation.serializer(), encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun conversationWithDefaultsRoundTrip() {
        val original = Conversation()
        val encoded = json.encodeToString(Conversation.serializer(), original)
        val decoded = json.decodeFromString(Conversation.serializer(), encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun conversationDeserializesSerialNames() {
        val serverJson = """
            {
                "conversationId": "conv-srv",
                "agent_id": "agent-1",
                "assistant_id": "asst-1",
                "top_p": 0.95,
                "frequency_penalty": 0.2,
                "presence_penalty": 0.1,
                "reasoning_effort": "medium",
                "web_search": false,
                "thinkingLevel": "basic"
            }
        """.trimIndent()
        val decoded = json.decodeFromString(Conversation.serializer(), serverJson)
        assertEquals("agent-1", decoded.agentId)
        assertEquals("asst-1", decoded.assistantId)
        assertEquals(0.95, decoded.topP)
        assertEquals(0.2, decoded.frequencyPenalty)
        assertEquals(0.1, decoded.presencePenalty)
        assertEquals("medium", decoded.reasoningEffort)
        assertEquals(false, decoded.webSearch)
        assertEquals("basic", decoded.thinkingLevel)
    }

    @Test
    fun endpointEnumSerializesToJsonString() {
        val original = Conversation(endpoint = "anthropic")
        val encoded = json.encodeToString(Conversation.serializer(), original)
        assertEquals(true, encoded.contains("\"anthropic\""), "Expected serialized name 'anthropic' in $encoded")
        val decoded = json.decodeFromString(Conversation.serializer(), encoded)
        assertEquals("anthropic", decoded.endpoint)
    }

    @Test
    fun conversationWithCustomEndpointRoundTrip() {
        val original = Conversation(
            conversationId = "conv-custom",
            endpoint = "OpenRouter",
            endpointType = "custom",
            model = "meta-llama/llama-3.3-70b-instruct:free",
        )
        val encoded = json.encodeToString(Conversation.serializer(), original)
        assertEquals(true, encoded.contains("\"OpenRouter\""), "Expected literal 'OpenRouter' in $encoded")
        assertEquals(true, encoded.contains("\"custom\""), "Expected 'custom' endpointType in $encoded")
        val decoded = json.decodeFromString(Conversation.serializer(), encoded)
        assertEquals("OpenRouter", decoded.endpoint)
        assertEquals("custom", decoded.endpointType)
    }
}
