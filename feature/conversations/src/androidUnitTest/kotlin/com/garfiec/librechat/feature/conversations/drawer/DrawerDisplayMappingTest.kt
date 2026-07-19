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
     * The mapping parses the timestamp but must NOT format it. Parsing is clock-independent, so
     * doing it once here beats doing it per row; formatting is clock-dependent, so doing it here
     * would freeze the label at whenever the mapping last ran — the stale-"Just now" bug.
     */
    @Test
    fun updatedAtIsParsedNotFormatted() {
        val convo = Conversation(
            conversationId = "c1",
            updatedAt = "2026-07-19T12:00:00Z",
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

    /** Malformed timestamps must not throw in the mapping — they degrade to a blank label. */
    @Test
    fun malformedUpdatedAtBecomesNull() {
        val convo = Conversation(conversationId = "c1", updatedAt = "not-a-timestamp")

        val data = convo.toDrawerDisplayData(activeConversationId = null, endpointConfigs = emptyMap())

        assertThat(data.updatedAt).isNull()
    }
}
