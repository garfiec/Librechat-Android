package com.garfiec.librechat.core.model.response

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PickerMimeTypesTest {

    private fun config(vararg patterns: String, endpoint: String? = null) =
        if (endpoint == null) {
            FileUploadConfig(supportedMimeTypes = patterns.toList())
        } else {
            FileUploadConfig(endpoints = mapOf(endpoint to EndpointFileConfig(supportedMimeTypes = patterns.toList())))
        }

    @Test
    fun noAllowlistIsUnrestricted() {
        assertEquals(emptyList(), FileUploadConfig().pickerMimeTypes())
    }

    @Test
    fun permissivePatternIsUnrestricted() {
        assertEquals(emptyList(), config(".*").pickerMimeTypes())
        assertEquals(emptyList(), config("^.*$").pickerMimeTypes())
    }

    @Test
    fun unrepresentablePatternIsUnrestricted() {
        // An admin allowing a type this build doesn't know about must not narrow the picker.
        assertEquals(emptyList(), config("^application/vnd\\.acme\\.widget$").pickerMimeTypes())
    }

    @Test
    fun oneUnrepresentablePatternDiscardsTheWholeAllowlist() {
        // Emitting only the pdf half would make the acme files unpickable.
        assertEquals(
            emptyList(),
            config("^application/pdf$", "^application/vnd\\.acme\\.widget$").pickerMimeTypes(),
        )
    }

    @Test
    fun exactPatternsTranslateToThoseTypes() {
        assertEquals(
            listOf("application/pdf", "image/png"),
            config("^application/pdf$", "^image/png$").pickerMimeTypes(),
        )
    }

    @Test
    fun familyWildcardExpandsToEveryKnownMemberOfThatFamily() {
        val types = config("^image/.*$").pickerMimeTypes()
        assertContains(types, "image/png")
        assertContains(types, "image/heic")
        assertTrue(types.all { it.startsWith("image/") })
    }

    @Test
    fun emlIsRepresentable() {
        assertEquals(listOf("message/rfc822"), config("^message/rfc822$").pickerMimeTypes())
    }

    @Test
    fun excelAliasesAreRepresentable() {
        // The server's default spreadsheet allowlist names aliases the picker must still resolve.
        assertEquals(listOf("application/xls"), config("^application/xls$").pickerMimeTypes())
        assertEquals(
            listOf("application/x-dos_ms_excel"),
            config("^application/x-dos_ms_excel$").pickerMimeTypes(),
        )
    }

    @Test
    fun endpointOverrideWinsOverDefault() {
        val config = FileUploadConfig(
            endpoints = mapOf(
                "default" to EndpointFileConfig(supportedMimeTypes = listOf("^image/png$")),
                "agents" to EndpointFileConfig(supportedMimeTypes = listOf("^application/pdf$")),
            ),
        )
        assertEquals(listOf("application/pdf"), config.pickerMimeTypes("agents"))
        assertEquals(listOf("image/png"), config.pickerMimeTypes("openAI"))
    }

    @Test
    fun malformedPatternIsUnrestricted() {
        assertEquals(emptyList(), config("^image/(png$").pickerMimeTypes())
    }
}
