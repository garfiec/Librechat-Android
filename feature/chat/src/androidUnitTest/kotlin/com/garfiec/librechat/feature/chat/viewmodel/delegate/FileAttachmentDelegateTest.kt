package com.garfiec.librechat.feature.chat.viewmodel.delegate

import com.garfiec.librechat.feature.chat.components.AttachedFile
import com.garfiec.librechat.feature.chat.viewmodel.ErrorOnlyHandle
import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Test

class FileAttachmentDelegateTest {

    private fun createDelegate(handle: ErrorOnlyHandle = mockk(relaxed = true)) = FileAttachmentDelegate(
        handle = handle,
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

    /** A file still uploading: no server fileId yet, not failed. */
    private fun pendingFile(id: String) = AttachedFile(
        uri = id,
        name = "$id.png",
        isImage = true,
        uploadProgress = 0.5f,
        fileId = null,
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

    @Test
    fun `waitForUploadsAndSend aborts with an error when an upload never completes`() = runTest {
        val handle = mockk<ErrorOnlyHandle>(relaxed = true)
        val delegate = createDelegate(handle)
        delegate.restoreAttachedFiles(listOf(pendingFile("stuck")))

        var sent = false
        delegate.waitForUploadsAndSend("hello") { sent = true }

        // The send must not fire (buildSendSpec would silently drop the not-yet-uploaded file);
        // the user gets a visible error instead.
        assertThat(sent).isFalse()
        verify { handle.setError(any()) }
    }

    @Test
    fun `waitForUploadsAndSend sends once every upload has completed`() = runTest {
        val handle = mockk<ErrorOnlyHandle>(relaxed = true)
        val delegate = createDelegate(handle)
        delegate.restoreAttachedFiles(listOf(serverFile("done")))

        var sentText: String? = null
        delegate.waitForUploadsAndSend("hello") { sentText = it }

        assertThat(sentText).isEqualTo("hello")
        verify(exactly = 0) { handle.setError(any()) }
    }
}
