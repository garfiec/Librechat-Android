package com.garfiec.librechat.shared.navigation

import com.garfiec.librechat.feature.chat.navigation.ArtifactShortcutViewer
import com.garfiec.librechat.feature.chat.navigation.Chat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DeepLinksTest {

    private fun uri(host: String, path: String? = null, query: Map<String, String> = emptyMap()) =
        DeepLinkUri(host = host, pathSegments = listOfNotNull(path), query = query)

    @Test
    fun conversationValidUuid_routesToChatRequiringAuth() {
        // conversationId is a server-generated uuidv4, not a Mongo ObjectID.
        val id = "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d"
        val resolution = DeepLinks.resolve(uri("conversation", id))
        val route = assertIs<DeepLinkResolution.Route>(resolution)
        assertTrue(route.requiresAuth)
        assertEquals(id, assertIs<Chat>(route.target).conversationId)
    }

    @Test
    fun conversationMalformedId_isNotRouted() {
        assertEquals(DeepLinkResolution.None, DeepLinks.resolve(uri("conversation", "not-a-uuid")))
        // A 24-hex Mongo ObjectID is NOT a conversationId — must be rejected (regression lock).
        assertEquals(DeepLinkResolution.None, DeepLinks.resolve(uri("conversation", "0123456789abcdef01234567")))
        assertEquals(DeepLinkResolution.None, DeepLinks.resolve(uri("conversation")))
    }

    @Test
    fun artifactValidUuid_routesToViewerOpenLoggedOut() {
        val id = "3f2504e0-4f89-41d3-9a0c-0305e82c3301"
        val resolution = DeepLinks.resolve(uri("artifact", id))
        val route = assertIs<DeepLinkResolution.Route>(resolution)
        assertTrue(!route.requiresAuth) // device-scoped snapshot renders without a session
        assertEquals(id, assertIs<ArtifactShortcutViewer>(route.target).snapshotId)
    }

    @Test
    fun artifactMalformedId_isNotRouted() {
        assertEquals(DeepLinkResolution.None, DeepLinks.resolve(uri("artifact", "12345")))
    }

    @Test
    fun oauth_isConsumedNotNavigated() {
        assertEquals(DeepLinkResolution.Consumed, DeepLinks.resolve(uri("oauth", query = mapOf("code" to "x"))))
    }

    @Test
    fun unknownHost_isNotRouted() {
        assertEquals(DeepLinkResolution.None, DeepLinks.resolve(uri("wat", "whatever")))
    }

    @Test
    fun nullHost_isNotRouted() {
        // The shape an opaque "librechat:foo" URI adapts to (host null, no segments, empty query).
        assertEquals(
            DeepLinkResolution.None,
            DeepLinks.resolve(DeepLinkUri(host = null, pathSegments = emptyList(), query = emptyMap())),
        )
    }
}
