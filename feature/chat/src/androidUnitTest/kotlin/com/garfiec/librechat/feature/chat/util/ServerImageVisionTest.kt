package com.garfiec.librechat.feature.chat.util

import com.garfiec.librechat.core.model.FileObject
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ServerImageVisionTest {

    private fun file(
        id: String,
        type: String,
        height: Int? = 100,
        name: String = "$id.bin",
    ) = FileObject(
        fileId = id,
        filename = name,
        filepath = "/uploads/$id",
        type = type,
        bytes = 1024,
        height = height,
    )

    @Test
    fun isImageType_matchesImageMimePrefixOnly() {
        assertThat(isImageType("image/png")).isTrue()
        assertThat(isImageType("image/svg+xml")).isTrue()
        assertThat(isImageType("application/pdf")).isFalse()
        assertThat(isImageType("")).isFalse()
    }

    @Test
    fun image_withHeight_isNotFlagged() {
        val names = visionUnreadableImageNames(listOf(file("a", "image/png", height = 200)))

        assertThat(names).isEmpty()
    }

    @Test
    fun image_withNullHeight_isFlaggedByName() {
        val names = visionUnreadableImageNames(
            listOf(file("a", "image/jpeg", height = null, name = "photo.jpg")),
        )

        assertThat(names).containsExactly("photo.jpg")
    }

    @Test
    fun image_withZeroHeight_isFlagged() {
        // Mirrors the server's `!file.height`: 0 is as unusable as null.
        val names = visionUnreadableImageNames(listOf(file("a", "image/png", height = 0)))

        assertThat(names).hasSize(1)
    }

    @Test
    fun nonImage_withNullHeight_isNotFlagged() {
        // Documents attach as context on agents — they have no vision-height requirement.
        val names = visionUnreadableImageNames(
            listOf(file("doc", "application/pdf", height = null, name = "notes.pdf")),
        )

        assertThat(names).isEmpty()
    }

    @Test
    fun mixedSelection_flagsOnlyHeightlessImages() {
        val names = visionUnreadableImageNames(
            listOf(
                file("ok", "image/png", height = 300),
                file("bad", "image/png", height = null, name = "scan.png"),
                file("pdf", "application/pdf", height = null),
            ),
        )

        assertThat(names).containsExactly("scan.png")
    }
}
