package com.garfiec.librechat.feature.chat.viewmodel.delegate

import com.garfiec.librechat.core.data.repository.FileRepository
import com.garfiec.librechat.core.model.Attachment
import com.garfiec.librechat.feature.chat.viewmodel.ChatStateHandle
import com.garfiec.librechat.feature.chat.viewmodel.OfficePreviewHandle
import com.garfiec.librechat.feature.chat.viewmodel.ChatUiState
import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import org.junit.Test

/**
 * Tests the office-preview upsert-by-file_id + NO-REGRESS guard (the critical
 * correctness bit): a terminal (`ready`/`failed`) record must never be downgraded
 * by a late-arriving `pending` for the same file_id, and a terminal incoming
 * always wins over an existing `pending`.
 */
class OfficePreviewDelegateTest {

    private fun delegate(): OfficePreviewDelegate {
        val flow = MutableStateFlow(ChatUiState())
        val handle = ChatStateHandle(flow, CoroutineScope(TestScope().coroutineContext))
        return OfficePreviewDelegate(OfficePreviewHandle(handle), mockk<FileRepository>())
    }

    private val docMime = "application/vnd.librechat.docx-preview"

    private fun att(status: String, text: String? = null) =
        Attachment(fileId = "f1", filename = "r.docx", type = docMime, status = status, text = text)

    @Test
    fun `appends a new record then merges over by file_id`() {
        val d = delegate()
        val out1 = d.upsertByFileId(emptyList(), att("pending"))
        assertThat(out1).hasSize(1)
        val out2 = d.upsertByFileId(out1, att("ready", text = "<html/>"))
        assertThat(out2).hasSize(1)
        assertThat(out2[0].status).isEqualTo("ready")
        assertThat(out2[0].text).isEqualTo("<html/>")
    }

    @Test
    fun `ready is NOT regressed by a late pending for the same file_id`() {
        val d = delegate()
        val ready = listOf(att("ready", text = "<html/>"))
        val out = d.upsertByFileId(ready, att("pending"))
        assertThat(out).hasSize(1)
        assertThat(out[0].status).isEqualTo("ready")
        assertThat(out[0].text).isEqualTo("<html/>")
    }

    @Test
    fun `failed is NOT regressed by a late pending`() {
        val d = delegate()
        val failed = listOf(att("failed"))
        val out = d.upsertByFileId(failed, att("pending"))
        assertThat(out[0].status).isEqualTo("failed")
    }

    @Test
    fun `terminal incoming wins over existing pending regardless of order`() {
        val d = delegate()
        val pending = listOf(att("pending"))
        val out = d.upsertByFileId(pending, att("ready", text = "x"))
        assertThat(out[0].status).isEqualTo("ready")
    }

    @Test
    fun `distinct file_ids each get their own record`() {
        val d = delegate()
        val a = Attachment(fileId = "f1", type = docMime, status = "pending")
        val b = Attachment(fileId = "f2", type = docMime, status = "pending")
        val out = d.upsertByFileId(d.upsertByFileId(emptyList(), a), b)
        assertThat(out.map { it.fileId }).containsExactly("f1", "f2").inOrder()
    }
}
