package com.garfiec.librechat.feature.agents.util

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the port of upstream's `normalizeServerName`.
 *
 * This is mirrored server logic with no runtime feedback: a wrong key produces a tool the server
 * cannot resolve, which surfaces as "Tool not found" at execution time rather than as anything a
 * save could report. Only a test can catch a drifting port.
 */
class McpServerNamesTest {

    @Test
    fun leavesAnAlreadySafeNameAlone() {
        listOf("filesystem", "my-server", "my_server", "server.v2", "Server_1").forEach { name ->
            assertEquals(name, normalizeMcpServerName(name), "safe name must round-trip unchanged")
        }
    }

    @Test
    fun replacesUnsafeCharactersWithUnderscores() {
        // The motivating case from upstream's own report.
        assertEquals("Google_Workspace", normalizeMcpServerName("Google Workspace"))
        assertEquals("a_b_c", normalizeMcpServerName("a/b:c"))
    }

    @Test
    fun trimsLeadingAndTrailingUnderscoresAfterReplacement() {
        assertEquals("server", normalizeMcpServerName(" server "))
        assertEquals("a_b", normalizeMcpServerName("!!a b!!"))
    }

    @Test
    fun doesNotTrimUnderscoresThatWereAlreadyThere() {
        // The early return fires first, so a name of only safe characters is never trimmed.
        assertEquals("_server_", normalizeMcpServerName("_server_"))
    }

    @Test
    fun hashesAnameThatNormalizesToNothing() {
        val normalized = normalizeMcpServerName("🙂🙂")
        assertEquals("server_", normalized.take("server_".length))
        assertEquals(true, normalized.removePrefix("server_").all { it.isDigit() })
    }

    @Test
    fun hashMatchesTheJavaScriptFold() {
        // "!!" -> both chars are 0x21. hash = ((0<<5)-0+33) = 33; then (33<<5)-33+33 = 1056.
        assertEquals("server_1056", normalizeMcpServerName("!!"))
    }

    @Test
    fun resolvesANormalizedNameBackToItsRawConfiguredForm() {
        val known = listOf("Google Workspace", "filesystem")
        assertEquals("Google Workspace", resolveRawMcpServerName("Google_Workspace", known))
        assertEquals("filesystem", resolveRawMcpServerName("filesystem", known))
    }

    @Test
    fun prefersAnExactMatchOverANormalizedOne() {
        // A deployment can legitimately configure both spellings. An exact hit must win, or
        // selecting one server silently rewrites it to the other.
        val known = listOf("Google Workspace", "Google_Workspace")
        assertEquals("Google_Workspace", resolveRawMcpServerName("Google_Workspace", known))
    }

    @Test
    fun leavesAnUnresolvableNameAlone() {
        // No server list loaded yet, or a server that was removed: the stored name must survive a
        // load/save round-trip rather than being dropped.
        assertEquals("whatever", resolveRawMcpServerName("whatever", emptyList()))
    }
}
