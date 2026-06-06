package com.garfiec.librechat.feature.chat.components.artifact

import kotlin.test.Test
import kotlin.test.assertEquals

class ArtifactTypeTest {

    @Test
    fun `office docx preview classifies as HTML`() {
        assertEquals(
            ArtifactType.HTML,
            ArtifactType.from("application/vnd.librechat.docx-preview"),
        )
    }

    @Test
    fun `office spreadsheet preview classifies as HTML`() {
        assertEquals(
            ArtifactType.HTML,
            ArtifactType.from("application/vnd.librechat.spreadsheet-preview"),
        )
    }

    @Test
    fun `office presentation preview classifies as HTML`() {
        assertEquals(
            ArtifactType.HTML,
            ArtifactType.from("application/vnd.librechat.presentation-preview"),
        )
    }

    @Test
    fun `plain html classifies as HTML`() {
        assertEquals(ArtifactType.HTML, ArtifactType.from("text/html"))
    }

    @Test
    fun `code-html classifies as HTML not CODE`() {
        assertEquals(ArtifactType.HTML, ArtifactType.from("application/vnd.code-html"))
    }

    @Test
    fun `unknown code mime still falls through to CODE`() {
        assertEquals(ArtifactType.CODE, ArtifactType.from("application/vnd.code"))
    }

    @Test
    fun `markdown classifies as MARKDOWN`() {
        assertEquals(ArtifactType.MARKDOWN, ArtifactType.from("text/markdown"))
    }
}
