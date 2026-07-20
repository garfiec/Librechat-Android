package com.garfiec.librechat.feature.conversations.drawer

import com.garfiec.librechat.core.model.Conversation
import com.garfiec.librechat.core.model.EndpointConfig
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.time.Instant

class DrawerDisplayMappingTest {

    @Test
    fun iconURLPrefersConversationOverEndpointConfig() {
        val convo = Conversation(
            conversationId = "c1",
            endpoint = "OpenRouter",
            iconURL = "https://convo-icon.example.com/icon.png",
        )
        val configs = mapOf(
            "OpenRouter" to EndpointConfig(iconURL = "https://config-icon.example.com/icon.png"),
        )

        val data = convo.toDrawerDisplayData(activeConversationId = null, endpointConfigs = configs)

        assertThat(data.endpointIconUrl).isEqualTo("https://convo-icon.example.com/icon.png")
    }

    @Test
    fun iconURLFallsBackToEndpointConfig() {
        val convo = Conversation(
            conversationId = "c1",
            endpoint = "OpenRouter",
            iconURL = null,
        )
        val configs = mapOf(
            "OpenRouter" to EndpointConfig(iconURL = "https://config-icon.example.com/icon.png"),
        )

        val data = convo.toDrawerDisplayData(activeConversationId = null, endpointConfigs = configs)

        assertThat(data.endpointIconUrl).isEqualTo("https://config-icon.example.com/icon.png")
    }

    @Test
    fun iconURLNullWhenNeitherSet() {
        val convo = Conversation(
            conversationId = "c1",
            endpoint = "OpenRouter",
            iconURL = null,
        )
        val configs = mapOf(
            "OpenRouter" to EndpointConfig(iconURL = null),
        )

        val data = convo.toDrawerDisplayData(activeConversationId = null, endpointConfigs = configs)

        assertThat(data.endpointIconUrl).isNull()
    }

    @Test
    fun iconURLNullWhenEndpointMissingFromConfig() {
        val convo = Conversation(
            conversationId = "c1",
            endpoint = "OpenRouter",
            iconURL = null,
        )

        val data = convo.toDrawerDisplayData(
            activeConversationId = null,
            endpointConfigs = emptyMap(),
        )

        assertThat(data.endpointIconUrl).isNull()
    }

    /**
     * The mapping carries the timestamp through untouched — it must NOT format it. Formatting is
     * clock-dependent, so doing it here would freeze the label at whenever the mapping last ran —
     * the stale-"Just now" bug. (Malformed wire timestamps are handled upstream, at
     * deserialization: see LenientInstantSerializerTest.)
     */
    @Test
    fun updatedAtIsCopiedNotFormatted() {
        val convo = Conversation(
            conversationId = "c1",
            updatedAt = Instant.parse("2026-07-19T12:00:00Z"),
        )

        val data = convo.toDrawerDisplayData(activeConversationId = null, endpointConfigs = emptyMap())

        assertThat(data.updatedAt).isEqualTo(Instant.parse("2026-07-19T12:00:00Z"))
    }

    @Test
    fun updatedAtNullSurvivesMapping() {
        val convo = Conversation(conversationId = "c1", updatedAt = null)

        val data = convo.toDrawerDisplayData(activeConversationId = null, endpointConfigs = emptyMap())

        assertThat(data.updatedAt).isNull()
    }
}
