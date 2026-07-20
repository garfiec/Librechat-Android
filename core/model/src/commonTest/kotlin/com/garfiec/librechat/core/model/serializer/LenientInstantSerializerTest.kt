package com.garfiec.librechat.core.model.serializer

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class LenientInstantSerializerTest {

    @Serializable
    private data class Host(
        @Serializable(with = LenientInstantSerializer::class)
        val at: Instant? = null,
    )

    /** Mirrors the NetworkModule client Json — the config every server payload decodes under. */
    private val networkJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = false
        explicitNulls = false
        coerceInputValues = true
    }

    /** Mirrors the ConversationExporter Json (explicitNulls stays at its default of true). */
    private val exportJson = Json { encodeDefaults = true }

    @Test
    fun decodesIsoTimestamps() {
        // The backend (Mongoose toISOString) always emits the fractional-ms Z form; the other
        // forms pin that any spec-valid ISO-8601 string keeps working.
        assertEquals(
            Instant.parse("2026-03-28T10:00:00Z"),
            networkJson.decodeFromString<Host>("""{"at":"2026-03-28T10:00:00.000Z"}""").at,
        )
        assertEquals(
            Instant.parse("2026-03-28T10:00:00.123Z"),
            networkJson.decodeFromString<Host>("""{"at":"2026-03-28T10:00:00.123Z"}""").at,
        )
        assertEquals(
            Instant.parse("2026-03-28T10:00:00Z"),
            networkJson.decodeFromString<Host>("""{"at":"2026-03-28T10:00:00+00:00"}""").at,
        )
    }

    /**
     * Unparseable input degrades to null instead of throwing. A Conversation rides inside larger
     * payloads (list pages, SSE Final events) — a strict serializer would fail the whole decode
     * over one bad field.
     */
    @Test
    fun malformedTimestampDecodesToNull() {
        assertNull(networkJson.decodeFromString<Host>("""{"at":"not-a-timestamp"}""").at)
    }

    /** Exports written by older app versions contain explicit `"updatedAt": null`. */
    @Test
    fun explicitJsonNullDecodesToNull() {
        assertNull(networkJson.decodeFromString<Host>("""{"at":null}""").at)
        assertNull(exportJson.decodeFromString<Host>("""{"at":null}""").at)
    }

    @Test
    fun absentKeyDecodesToNull() {
        assertNull(networkJson.decodeFromString<Host>("""{}""").at)
    }

    /** ISO string on the wire — an epoch encoding would break old-app import of new exports. */
    @Test
    fun encodesAsIsoString() {
        assertEquals(
            """{"at":"2026-03-28T10:00:00Z"}""",
            exportJson.encodeToString(Host(Instant.parse("2026-03-28T10:00:00Z"))),
        )
        assertEquals(
            """{"at":"2026-03-28T10:00:00.123Z"}""",
            exportJson.encodeToString(Host(Instant.parse("2026-03-28T10:00:00.123Z"))),
        )
    }

    /** Under the exporter's config a null still emits as an explicit JSON null, as it does today. */
    @Test
    fun exportConfigEncodesNullExplicitly() {
        assertEquals("""{"at":null}""", exportJson.encodeToString(Host(null)))
    }

    /** Under the network config a null field is omitted — request wire shapes stay unchanged. */
    @Test
    fun networkConfigOmitsNull() {
        assertEquals("""{}""", networkJson.encodeToString(Host(null)))
    }

    @Test
    fun roundTripPreservesValue() {
        val original = Host(Instant.parse("2026-03-28T10:00:00.500Z"))
        assertEquals(original, exportJson.decodeFromString<Host>(exportJson.encodeToString(original)))
    }
}
