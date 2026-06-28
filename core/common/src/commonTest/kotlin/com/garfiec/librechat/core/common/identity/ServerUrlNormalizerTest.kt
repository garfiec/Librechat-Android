package com.garfiec.librechat.core.common.identity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ServerUrlNormalizerTest {

    // region collapses cosmetic differences (same deployment -> same key)

    @Test
    fun trailingSlashCollapses() {
        assertEquals("https://chat.example.com", normalizeServerUrl("https://chat.example.com/"))
        assertEquals("https://chat.example.com", normalizeServerUrl("https://chat.example.com///"))
    }

    @Test
    fun hostCaseCollapses() {
        assertEquals("https://chat.example.com", normalizeServerUrl("https://Chat.Example.COM"))
    }

    @Test
    fun schemeCaseCollapses() {
        assertEquals("https://chat.example.com", normalizeServerUrl("HTTPS://chat.example.com"))
    }

    @Test
    fun defaultPortElided() {
        assertEquals("https://chat.example.com", normalizeServerUrl("https://chat.example.com:443"))
        assertEquals("http://chat.example.com", normalizeServerUrl("http://chat.example.com:80"))
    }

    @Test
    fun missingSchemeDefaultsToHttps() {
        assertEquals("https://chat.example.com", normalizeServerUrl("chat.example.com"))
    }

    @Test
    fun queryAndFragmentDropped() {
        assertEquals("https://chat.example.com", normalizeServerUrl("https://chat.example.com/?a=1#frag"))
        assertEquals(
            "https://chat.example.com/librechat",
            normalizeServerUrl("https://chat.example.com/librechat?x=y"),
        )
    }

    @Test
    fun userInfoStripped() {
        assertEquals("https://chat.example.com", normalizeServerUrl("https://user:pass@chat.example.com"))
    }

    @Test
    fun whitespaceTrimmed() {
        assertEquals("https://chat.example.com", normalizeServerUrl("  https://chat.example.com/  "))
    }

    // endregion

    // region preserves identity-significant differences (different deployment -> different key)

    @Test
    fun subpathRetained() {
        assertEquals("https://host.com/librechat", normalizeServerUrl("https://host.com/librechat/"))
    }

    @Test
    fun differentSubpathsStayDistinct() {
        val a = normalizeServerUrl("https://host.com/librechat")
        val b = normalizeServerUrl("https://host.com/other")
        val root = normalizeServerUrl("https://host.com")
        assertEquals(3, setOf(a, b, root).size)
    }

    @Test
    fun pathCasePreserved() {
        // Path is server-significant; do NOT fold case the way host is folded.
        val a = normalizeServerUrl("https://host.com/LibreChat")
        val b = normalizeServerUrl("https://host.com/librechat")
        assertEquals("https://host.com/LibreChat", a)
        assertEquals("https://host.com/librechat", b)
    }

    @Test
    fun nonDefaultPortPreserved() {
        assertEquals("https://host.com:8443", normalizeServerUrl("https://host.com:8443"))
        assertEquals("http://host.com:3080", normalizeServerUrl("http://host.com:3080"))
    }

    @Test
    fun schemeDifferenceStaysDistinct() {
        val http = normalizeServerUrl("http://host.com")
        val https = normalizeServerUrl("https://host.com")
        assertEquals("http://host.com", http)
        assertEquals("https://host.com", https)
    }

    @Test
    fun ipv6LiteralPreserved() {
        assertEquals("https://[::1]:8443", normalizeServerUrl("https://[::1]:8443/"))
        assertEquals("https://[::1]", normalizeServerUrl("https://[::1]:443/"))
    }

    // endregion

    // region rejects garbage

    @Test
    fun blankRejected() {
        assertFailsWith<IllegalArgumentException> { normalizeServerUrl("   ") }
    }

    @Test
    fun unsupportedSchemeRejected() {
        assertFailsWith<IllegalArgumentException> { normalizeServerUrl("ftp://host.com") }
    }

    @Test
    fun hostlessRejected() {
        assertFailsWith<IllegalArgumentException> { normalizeServerUrl("https:///path") }
    }

    // endregion
}
