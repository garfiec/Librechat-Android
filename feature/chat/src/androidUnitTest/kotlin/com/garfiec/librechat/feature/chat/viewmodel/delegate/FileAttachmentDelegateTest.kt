package com.garfiec.librechat.feature.chat.viewmodel.delegate

import com.garfiec.librechat.feature.chat.components.AttachedFile
import com.garfiec.librechat.feature.chat.viewmodel.ChatStateHandle
import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import org.junit.Test

class FileAttachmentDelegateTest {

    private fun createDelegate() = FileAttachmentDelegate(
        stateHandle = mockk<ChatStateHandle>(relaxed = true),
        appContext = mockk(relaxed = true),
        fileRepository = mockk(relaxed = true),
        ioDispatcher = Dispatchers.Unconfined,
    )

    private fun serverFile(id: String) = AttachedFile(
        uri = id,
        name = "$id.png",
        isImage = true,
        uploadProgress = 1f,
        fileId = id,
        filepath = "/files/$id.png",
        type = "image/png",
    )

    @Test
    fun `addPreUploadedFiles appends to existing tray`() {
        val delegate = createDelegate()
        delegate.addPreUploadedFiles(listOf(serverFile("a")))
        delegate.addPreUploadedFiles(listOf(serverFile("b")))

        assertThat(delegate.attachedFiles.value.mapNotNull { it.fileId })
            .containsExactly("a", "b")
    }

    @Test
    fun `addPreUploadedFiles dedupes by fileId`() {
        val delegate = createDelegate()
        delegate.addPreUploadedFiles(listOf(serverFile("a"), serverFile("b")))
        // Re-adding "a" (already attached) plus a new "c" should only add "c".
        delegate.addPreUploadedFiles(listOf(serverFile("a"), serverFile("c")))

        assertThat(delegate.attachedFiles.value.mapNotNull { it.fileId })
            .containsExactly("a", "b", "c")
    }
}
