package com.garfiec.librechat.core.common.identity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AccountIdDerivationTest {

    @Test
    fun serverIdIsStableAndCompact() {
        val id = deriveServerId("https://chat.example.com")
        assertEquals(id, deriveServerId("https://chat.example.com"))
        assertEquals(16, id.value.length)
        assertTrue(id.value.all { it in "0123456789abcdef" }, "expected lowercase hex")
    }

    @Test
    fun equivalentUrlsCollapseToSameServerId() {
        // Same deployment differing only by case / default port / trailing slash / query / fragment.
        val a = deriveServerId("HTTPS://Chat.Example.com:443/librechat/?x=1#f")
        val b = deriveServerId("https://chat.example.com/librechat")
        assertEquals(a, b)
    }

    @Test
    fun differentDeploymentsGetDifferentServerIds() {
        assertNotEquals(deriveServerId("https://host/librechat"), deriveServerId("https://host/other"))
        assertNotEquals(deriveServerId("https://host/librechat"), deriveServerId("https://host"))
        assertNotEquals(deriveServerId("https://a.example.com"), deriveServerId("https://b.example.com"))
    }

    @Test
    fun accountIdComposesServerIdAndUserKey() {
        val serverId = deriveServerId("https://chat.example.com")
        val accountId = deriveAccountId(serverId, "mongo-123")
        assertEquals("${serverId.value}:mongo-123", accountId.value)
    }

    @Test
    fun sameUserDifferentServersAreDistinctAccounts() {
        val onA = deriveAccountId(deriveServerId("https://a.example.com"), "mongo-123")
        val onB = deriveAccountId(deriveServerId("https://b.example.com"), "mongo-123")
        assertNotEquals(onA, onB)
    }

    @Test
    fun blankUserKeyRejected() {
        assertFailsWith<IllegalArgumentException> {
            deriveAccountId(deriveServerId("https://chat.example.com"), "  ")
        }
    }
}
