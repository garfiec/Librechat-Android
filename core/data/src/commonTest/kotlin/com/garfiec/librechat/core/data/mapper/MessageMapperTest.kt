package com.garfiec.librechat.core.data.mapper

import com.garfiec.librechat.core.model.Message
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
}
