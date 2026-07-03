package com.garfiec.librechat.core.data.mapper

import com.garfiec.librechat.core.model.ContentType
import com.garfiec.librechat.core.model.Message
import com.garfiec.librechat.core.model.content.MessageContentPart
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MessageMapperTest {

    private fun baseMessage(quotes: List<String>?) = Message(
        messageId = "m1",
        conversationId = "c1",
        text = "hello",
        isCreatedByUser = true,
        quotes = quotes,
    )

    @Test
    fun quotes_roundTripThroughEntity() {
        val quotes = listOf("first excerpt", "second \"quoted\" excerpt")
        val restored = baseMessage(quotes).toEntity().toModel()
        assertEquals(quotes, restored.quotes)
    }

    @Test
    fun quotes_null_staysNull() {
        val restored = baseMessage(null).toEntity().toModel()
        assertNull(restored.quotes)
    }

    @Test
    fun quotes_emptyList_roundTripsAsEmpty() {
        val restored = baseMessage(emptyList()).toEntity().toModel()
        assertEquals(emptyList(), restored.quotes)
    }

    @Test
    fun contentParts_agentAttribution_roundTripsThroughEntity() {
        // Compare Models persistence: the per-part agentId/groupId attribution must
        // survive the Room round-trip, or reopening a comparison can't restore its panes.
        val message = Message(
            messageId = "m1",
            conversationId = "c1",
            content = listOf(
                MessageContentPart(type = ContentType.TEXT, text = "primary", agentId = "agent_a", groupId = 0),
                MessageContentPart(type = ContentType.TEXT, text = "secondary", agentId = "agent_a____1", groupId = 1),
            ),
        )

        val restored = message.toEntity().toModel()

        val parts = restored.content
        assertEquals(2, parts?.size)
        assertEquals("agent_a", parts?.get(0)?.agentId)
        assertEquals(0, parts?.get(0)?.groupId)
        assertEquals("agent_a____1", parts?.get(1)?.agentId)
        assertEquals(1, parts?.get(1)?.groupId)
    }
}
