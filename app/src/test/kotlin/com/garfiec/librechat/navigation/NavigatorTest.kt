package com.garfiec.librechat.navigation

import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.garfiec.librechat.feature.agents.navigation.AgentMarketplace
import com.garfiec.librechat.feature.auth.navigation.Login
import com.garfiec.librechat.feature.auth.navigation.ServerUrl
import com.garfiec.librechat.feature.chat.navigation.Chat
import com.garfiec.librechat.feature.chat.navigation.NewChat
import com.garfiec.librechat.feature.settings.navigation.ProviderKeys
import com.garfiec.librechat.feature.settings.navigation.SettingsTabbed
import com.garfiec.librechat.shared.navigation.Navigator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigatorTest {

    private fun createNavigator(vararg keys: NavKey): Navigator {
        return Navigator(NavBackStack(*keys))
    }

    @Test
    fun `currentRoute returns last entry`() {
        val navigator = createNavigator(NewChat())
        assertEquals(NewChat(), navigator.currentRoute)
    }

    @Test
    fun `currentRoute returns null for empty stack`() {
        val navigator = createNavigator()
        assertNull(navigator.currentRoute)
    }

    @Test
    fun `isInAuthFlow returns true for auth routes`() {
        val navigator = createNavigator(ServerUrl)
        assertTrue(navigator.isInAuthFlow)
    }

    @Test
    fun `isInAuthFlow returns true for nested auth routes`() {
        val navigator = createNavigator(ServerUrl, Login)
        assertTrue(navigator.isInAuthFlow)
    }

    @Test
    fun `isInAuthFlow returns false for non-auth routes`() {
        val navigator = createNavigator(NewChat())
        assertFalse(navigator.isInAuthFlow)
    }

    @Test
    fun `navigate adds route to back stack`() {
        val navigator = createNavigator(NewChat())
        navigator.navigate(SettingsTabbed)
        assertEquals(listOf(NewChat(), SettingsTabbed), navigator.backStack.toList())
    }

    @Test
    fun `goBack removes top entry`() {
        val navigator = createNavigator(NewChat(), SettingsTabbed)
        navigator.goBack()
        assertEquals(listOf(NewChat()), navigator.backStack.toList())
    }

    @Test
    fun `goBack on empty stack does not crash`() {
        val navigator = createNavigator()
        navigator.goBack()
        assertTrue(navigator.backStack.isEmpty())
    }

    @Test
    fun `navigateToChat with conversationId adds Chat route`() {
        val navigator = createNavigator(NewChat())
        navigator.navigateToChat("conv-123")
        assertEquals(
            listOf(NewChat(), Chat(conversationId = "conv-123")),
            navigator.backStack.toList(),
        )
    }

    @Test
    fun `navigateToChat replaces current chat`() {
        val navigator = createNavigator(NewChat(), Chat("conv-1"))
        navigator.navigateToChat("conv-2")
        assertEquals(
            listOf(NewChat(), Chat(conversationId = "conv-2")),
            navigator.backStack.toList(),
        )
    }

    @Test
    fun `navigateToChat does not replace non-chat route`() {
        val navigator = createNavigator(NewChat(), SettingsTabbed)
        navigator.navigateToChat("conv-1")
        assertEquals(
            listOf(NewChat(), SettingsTabbed, Chat(conversationId = "conv-1")),
            navigator.backStack.toList(),
        )
    }

    @Test
    fun `navigateToTopLevel pops to root and replaces`() {
        val navigator = createNavigator(NewChat(), Chat("conv-1"), SettingsTabbed)
        navigator.navigateToTopLevel(AgentMarketplace)
        assertEquals(listOf(AgentMarketplace), navigator.backStack.toList())
    }

    @Test
    fun `navigateToTopLevel with same route does not duplicate`() {
        val navigator = createNavigator(NewChat())
        navigator.navigateToTopLevel(NewChat())
        assertEquals(listOf(NewChat()), navigator.backStack.toList())
    }

    @Test
    fun `navigateToTopLevel replaces different root`() {
        val navigator = createNavigator(NewChat())
        navigator.navigateToTopLevel(AgentMarketplace)
        assertEquals(listOf(AgentMarketplace), navigator.backStack.toList())
    }

    @Test
    fun `navigateToTopLevel carries NewChat agentId over a bare NewChat root (F2)`() {
        // Regression: Start Chat on an agent → NewChat(agentId) must REPLACE the bare
        // landing NewChat(), not be dropped by a class-only dedup.
        val navigator = createNavigator(NewChat())
        navigator.navigateToTopLevel(NewChat(agentId = "agent_X"))
        assertEquals(listOf(NewChat(agentId = "agent_X")), navigator.backStack.toList())
        assertEquals("agent_X", (navigator.backStack.last() as NewChat).agentId)
    }

    @Test
    fun `navigateToTopLevel with identical NewChat agentId is a no-op`() {
        val navigator = createNavigator(NewChat(agentId = "agent_X"))
        navigator.navigateToTopLevel(NewChat(agentId = "agent_X"))
        assertEquals(listOf(NewChat(agentId = "agent_X")), navigator.backStack.toList())
    }

    @Test
    fun `navigateToAuth clears stack and adds ServerUrl`() {
        val navigator = createNavigator(NewChat(), Chat("conv-1"), SettingsTabbed)
        navigator.navigateToAuth()
        assertEquals(listOf(ServerUrl), navigator.backStack.toList())
    }

    @Test
    fun `navigateToChat no-arg clears stack and adds NewChat()`() {
        val navigator = createNavigator(ServerUrl, Login)
        navigator.navigateToChat()
        assertEquals(listOf(NewChat()), navigator.backStack.toList())
    }

    @Test
    fun `navigateToProviderKeys adds route when not on top`() {
        val navigator = createNavigator(NewChat())
        navigator.navigateToProviderKeys("openAI")
        assertEquals(
            listOf(NewChat(), ProviderKeys(pendingDialogEndpoint = "openAI")),
            navigator.backStack.toList(),
        )
    }

    @Test
    fun `navigateToProviderKeys is no-op when same endpoint already on top`() {
        val navigator = createNavigator(NewChat(), ProviderKeys(pendingDialogEndpoint = "openAI"))
        navigator.navigateToProviderKeys("openAI")
        assertEquals(
            listOf(NewChat(), ProviderKeys(pendingDialogEndpoint = "openAI")),
            navigator.backStack.toList(),
        )
    }

    @Test
    fun `navigateToProviderKeys replaces top when different endpoint already on top`() {
        val navigator = createNavigator(NewChat(), ProviderKeys(pendingDialogEndpoint = "openAI"))
        navigator.navigateToProviderKeys("anthropic")
        assertEquals(
            listOf(NewChat(), ProviderKeys(pendingDialogEndpoint = "anthropic")),
            navigator.backStack.toList(),
        )
    }

    @Test
    fun `navigateToProviderKeys with null endpoint dedupes against null-top`() {
        val navigator = createNavigator(NewChat(), ProviderKeys(pendingDialogEndpoint = null))
        navigator.navigateToProviderKeys(null)
        assertEquals(
            listOf(NewChat(), ProviderKeys(pendingDialogEndpoint = null)),
            navigator.backStack.toList(),
        )
    }
}
