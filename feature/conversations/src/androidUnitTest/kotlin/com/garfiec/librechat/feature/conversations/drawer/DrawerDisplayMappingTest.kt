package com.garfiec.librechat.feature.conversations.drawer

import com.garfiec.librechat.core.model.Conversation
import com.garfiec.librechat.core.model.EndpointConfig
import com.google.common.truth.Truth.assertThat
import org.junit.Test

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
}
