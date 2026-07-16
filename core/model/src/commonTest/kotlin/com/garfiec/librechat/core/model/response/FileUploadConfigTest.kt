package com.garfiec.librechat.core.model.response

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FileUploadConfigTest {

    @Test
    fun effectiveLimitFallsBackToDefaultEndpointForUnlistedEndpoint() {
        // The stock backend puts the real limit under endpoints["default"], not a top-level field.
        // openAI is not in the base endpoints map, so it must resolve through "default".
        val config = FileUploadConfig(
            endpoints = mapOf("default" to EndpointFileConfig(fileSizeLimit = 10_000L)),
        )
        assertEquals(10_000L, config.effectiveFileSizeLimit("openAI"))
    }

    @Test
    fun effectiveLimitPrefersPerEndpointOverrideOverDefault() {
        val config = FileUploadConfig(
            endpoints = mapOf(
                "default" to EndpointFileConfig(fileSizeLimit = 10_000L),
                "anthropic" to EndpointFileConfig(fileSizeLimit = 5_000L),
            ),
        )
        assertEquals(5_000L, config.effectiveFileSizeLimit("anthropic"))
        assertEquals(10_000L, config.effectiveFileSizeLimit("openAI"))
    }

    @Test
    fun effectiveLimitIgnoresNeverSentTopLevelWhenDefaultPresent() {
        // A non-standard top-level fileSizeLimit must not shadow the authoritative default entry.
        val config = FileUploadConfig(
            fileSizeLimit = 99_000L,
            endpoints = mapOf("default" to EndpointFileConfig(fileSizeLimit = 10_000L)),
        )
        assertEquals(10_000L, config.effectiveFileSizeLimit("openAI"))
    }

    @Test
    fun effectiveLimitUsesTopLevelOnlyAsLastResort() {
        // No endpoints map at all (non-standard backend): fall back to top-level if present.
        assertEquals(7_000L, FileUploadConfig(fileSizeLimit = 7_000L).effectiveFileSizeLimit("openAI"))
    }

    @Test
    fun effectiveLimitNullWhenNothingConfigured() {
        assertNull(FileUploadConfig().effectiveFileSizeLimit("openAI"))
        assertNull(FileUploadConfig().effectiveFileSizeLimit(null))
        // Endpoints map present but neither the endpoint nor "default" carries a limit.
        val config = FileUploadConfig(
            endpoints = mapOf("openAI" to EndpointFileConfig(fileSizeLimit = null)),
        )
        assertNull(config.effectiveFileSizeLimit("openAI"))
    }
}
