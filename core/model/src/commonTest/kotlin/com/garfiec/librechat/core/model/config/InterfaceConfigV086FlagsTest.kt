package com.garfiec.librechat.core.model.config

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers the v0.8.6 `/api/config` interface feature-flag detection added in the sync:
 * `skills` (raw JSON, bool-or-object), `buildInfo`, `autoSubmitFromUrl`, `retentionMode`.
 * These are parse-surface-only (no gating UI yet), so the contract under test is purely
 * "the server's shape decodes into the right typed fields and forward-compat holds".
 */
class InterfaceConfigV086FlagsTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun decodesV086FlagsFromServerJson_objectSkills() {
        val serverJson = """
            {
                "skills": { "use": true, "create": false, "share": true },
                "buildInfo": true,
                "autoSubmitFromUrl": false,
                "retentionMode": "temporary"
            }
        """.trimIndent()
        val cfg = json.decodeFromString(InterfaceConfig.serializer(), serverJson)

        // skills retained as raw JSON (bool | object), like prompts/agents/remoteAgents
        val skills = cfg.skills
        assertNotNull(skills)
        assertTrue(skills is JsonObject)
        assertEquals(JsonPrimitive(true), (skills as JsonObject)["use"])

        assertEquals(true, cfg.buildInfo)
        assertEquals(false, cfg.autoSubmitFromUrl)
        assertEquals("temporary", cfg.retentionMode)
    }

    @Test
    fun decodesV086FlagsFromServerJson_boolSkills() {
        // skills can also arrive as a bare boolean — must not throw.
        val serverJson = """{ "skills": true, "buildInfo": false }"""
        val cfg = json.decodeFromString(InterfaceConfig.serializer(), serverJson)
        assertEquals(JsonPrimitive(true), cfg.skills)
        assertEquals(false, cfg.buildInfo)
    }

    @Test
    fun v086FlagsDefaultNullOnOlderServer() {
        // A pre-v0.8.6 server omits all four — they must default to null (detect-only,
        // so absence == feature unavailable), and the existing flags keep their defaults.
        val serverJson = """{ "endpointsMenu": true, "modelSelect": true }"""
        val cfg = json.decodeFromString(InterfaceConfig.serializer(), serverJson)
        assertNull(cfg.skills)
        assertNull(cfg.buildInfo)
        assertNull(cfg.autoSubmitFromUrl)
        assertNull(cfg.retentionMode)
        // sanity: pre-existing fields unaffected
        assertEquals(true, cfg.temporaryChat)
    }

    @Test
    fun decodesNestedUnderStartupConfigInterfaceKey() {
        // The flags live under StartupConfig."interface" — verify the nesting + SerialName.
        val serverJson = """
            {
                "interface": {
                    "skills": { "use": true },
                    "buildInfo": true
                }
            }
        """.trimIndent()
        val startup = json.decodeFromString(StartupConfig.serializer(), serverJson)
        val iface = startup.interfaceConfig
        assertNotNull(iface)
        assertEquals(true, iface.buildInfo)
        assertTrue(iface.skills is JsonObject)
    }

    @Test
    fun interfaceConfigRoundTrip() {
        val original = InterfaceConfig(
            skills = JsonPrimitive(true),
            buildInfo = true,
            autoSubmitFromUrl = false,
            retentionMode = "all",
        )
        val encoded = json.encodeToString(InterfaceConfig.serializer(), original)
        val decoded = json.decodeFromString(InterfaceConfig.serializer(), encoded)
        assertEquals(original, decoded)
    }
}
