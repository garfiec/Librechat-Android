package com.garfiec.librechat.feature.chat.components

import com.garfiec.librechat.core.model.Attachment
import com.garfiec.librechat.feature.chat.components.artifact.ArtifactType
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ToolCallAttachmentsTest {

    // ─── partitionToolCallAttachments ──────────────────────────────

    @Test
    fun `filters to the given tool call id`() {
        val attachments = listOf(
            Attachment(fileId = "a", filename = "a.pdf", type = "application/pdf", toolCallId = "call_1"),
            Attachment(fileId = "b", filename = "b.pdf", type = "application/pdf", toolCallId = "call_2"),
        )

        val result = partitionToolCallAttachments(attachments, "call_1")

        assertThat(result).hasSize(1)
        assertThat(result.single().attachment.fileId).isEqualTo("a")
    }

    @Test
    fun `null tool call id yields nothing`() {
        val attachments = listOf(
            Attachment(fileId = "a", filename = "a.pdf", type = "application/pdf", toolCallId = "call_1"),
        )
        assertThat(partitionToolCallAttachments(attachments, null)).isEmpty()
    }

    @Test
    fun `skips pseudo attachment types`() {
        val attachments = listOf(
            Attachment(fileId = "w", type = "web_search", toolCallId = "c"),
            Attachment(fileId = "f", type = "file_search", toolCallId = "c"),
            Attachment(fileId = "m", type = "memory", toolCallId = "c"),
            Attachment(fileId = "u", type = "ui_resources", toolCallId = "c"),
        )
        assertThat(partitionToolCallAttachments(attachments, "c")).isEmpty()
    }

    @Test
    fun `skips office preview attachments`() {
        val attachments = listOf(
            Attachment(
                fileId = "d",
                filename = "report.docx",
                type = ArtifactType.DEFAULT_OFFICE_PREVIEW_MIME,
                toolCallId = "c",
            ),
        )
        assertThat(partitionToolCallAttachments(attachments, "c")).isEmpty()
    }

    @Test
    fun `skips sandbox placeholder leaves`() {
        val attachments = listOf(
            Attachment(fileId = "p", filename = "_.dirkeep-88b30b", toolCallId = "c"),
            Attachment(fileId = "q", filename = "_.gitkeep-abc123", toolCallId = "c"),
        )
        assertThat(partitionToolCallAttachments(attachments, "c")).isEmpty()
    }

    @Test
    fun `dedups by file id keeping first`() {
        val attachments = listOf(
            Attachment(fileId = "same", filename = "x.pdf", type = "application/pdf", toolCallId = "c"),
            Attachment(fileId = "same", filename = "x-copy.pdf", type = "application/pdf", toolCallId = "c"),
        )
        val result = partitionToolCallAttachments(attachments, "c")
        assertThat(result).hasSize(1)
        assertThat(result.single().attachment.filename).isEqualTo("x.pdf")
    }

    @Test
    fun `classifies image artifact pdf and file buckets`() {
        val attachments = listOf(
            Attachment(fileId = "img", filename = "plot.png", type = "image/png", toolCallId = "c"),
            Attachment(fileId = "py", filename = "make.py", type = "text/x-python", text = "print(1)", toolCallId = "c"),
            Attachment(fileId = "pdf", filename = "receipt.pdf", type = "application/pdf", toolCallId = "c"),
            Attachment(fileId = "csv", filename = "data.csv", type = "text/csv", toolCallId = "c"),
        )

        val result = partitionToolCallAttachments(attachments, "c")

        assertThat(result.filterIsInstance<ToolAttachment.Image>().map { it.attachment.fileId })
            .containsExactly("img")
        assertThat(result.filterIsInstance<ToolAttachment.ArtifactContent>().map { it.attachment.fileId })
            .containsExactly("py")
        assertThat(result.filterIsInstance<ToolAttachment.Pdf>().map { it.attachment.fileId })
            .containsExactly("pdf")
        assertThat(result.filterIsInstance<ToolAttachment.File>().map { it.attachment.fileId })
            .containsExactly("csv")
    }

    @Test
    fun `pdf by extension without mime is a pdf bucket`() {
        val attachments = listOf(
            Attachment(fileId = "p", filename = "invoice.PDF", type = null, toolCallId = "c"),
        )
        val result = partitionToolCallAttachments(attachments, "c")
        assertThat(result.single()).isInstanceOf(ToolAttachment.Pdf::class.java)
    }

    @Test
    fun `pdf without a file id falls back to a download chip`() {
        // No fileId → nothing to download for the native preview, so it stays a file chip.
        val attachments = listOf(
            Attachment(fileId = null, filename = "receipt.pdf", type = "application/pdf", toolCallId = "c"),
        )
        val result = partitionToolCallAttachments(attachments, "c")
        assertThat(result.single()).isInstanceOf(ToolAttachment.File::class.java)
    }

    @Test
    fun `code file with no extracted text falls back to file chip`() {
        val attachments = listOf(
            Attachment(fileId = "py", filename = "make.py", type = "text/x-python", text = null, toolCallId = "c"),
        )
        val result = partitionToolCallAttachments(attachments, "c")
        assertThat(result.single()).isInstanceOf(ToolAttachment.File::class.java)
    }

    // ─── looksLikePdf ──────────────────────────────────────────────

    @Test
    fun `looksLikePdf accepts a standard header`() {
        assertThat(looksLikePdf("%PDF-1.7\n%âãÏÓ".encodeToByteArray())).isTrue()
    }

    @Test
    fun `looksLikePdf finds a header after leading whitespace within the first kilobyte`() {
        val bytes = ByteArray(600) { ' '.code.toByte() } + "%PDF-1.5".encodeToByteArray()
        assertThat(looksLikePdf(bytes)).isTrue()
    }

    @Test
    fun `looksLikePdf ignores a header past the first kilobyte`() {
        val bytes = ByteArray(2000) { ' '.code.toByte() } + "%PDF-1.5".encodeToByteArray()
        assertThat(looksLikePdf(bytes)).isFalse()
    }

    @Test
    fun `looksLikePdf rejects an html error body`() {
        assertThat(looksLikePdf("<!DOCTYPE html><html><body>Not Found</body></html>".encodeToByteArray()))
            .isFalse()
    }

    @Test
    fun `looksLikePdf rejects empty bytes`() {
        assertThat(looksLikePdf(ByteArray(0))).isFalse()
    }

    // ─── displayFilename ───────────────────────────────────────────

    @Test
    fun `displayFilename restores sanitized dotfile with extension`() {
        assertThat(displayFilename("_.config-abcdef.txt")).isEqualTo(".config.txt")
    }

    @Test
    fun `displayFilename restores sanitized dotfile without extension`() {
        assertThat(displayFilename("_.dirkeep-88b30b")).isEqualTo(".dirkeep")
    }

    @Test
    fun `displayFilename passes ordinary names through`() {
        assertThat(displayFilename("report-deadbe.csv")).isEqualTo("report-deadbe.csv")
        assertThat(displayFilename("dummy_receipt.pdf")).isEqualTo("dummy_receipt.pdf")
    }

    // ─── attachmentToArtifact ──────────────────────────────────────

    @Test
    fun `python file maps to fenced markdown artifact`() {
        val artifact = attachmentToArtifact(
            Attachment(fileId = "py", filename = "make_receipt.py", text = "print('hi')"),
        )
        assertThat(artifact).isNotNull()
        assertThat(artifact!!.type).isEqualTo("text/markdown")
        assertThat(artifact.content).isEqualTo("```py\nprint('hi')\n```")
        assertThat(artifact.title).isEqualTo("make_receipt.py")
    }

    @Test
    fun `html file maps to html artifact`() {
        val artifact = attachmentToArtifact(
            Attachment(fileId = "h", filename = "page.html", text = "<h1>hi</h1>"),
        )
        assertThat(artifact?.type).isEqualTo("text/html")
        assertThat(artifact?.content).isEqualTo("<h1>hi</h1>")
    }

    @Test
    fun `mermaid file maps to mermaid artifact`() {
        val artifact = attachmentToArtifact(
            Attachment(fileId = "m", filename = "diagram.mmd", text = "graph TD; A-->B"),
        )
        assertThat(artifact?.type).isEqualTo("application/vnd.mermaid")
    }

    @Test
    fun `blank text yields no artifact`() {
        assertThat(attachmentToArtifact(Attachment(filename = "x.md", text = "  "))).isNull()
        assertThat(attachmentToArtifact(Attachment(filename = "x.html", text = null))).isNull()
    }

    @Test
    fun `unknown extension yields no artifact`() {
        assertThat(attachmentToArtifact(Attachment(filename = "receipt.pdf", text = "%PDF"))).isNull()
    }

    // ─── image classification ──────────────────────────────────────

    @Test
    fun `image mime routes to the image bucket without dimensions`() {
        val result = partitionToolCallAttachments(
            listOf(Attachment(fileId = "i", filename = "plot.png", type = "image/png", toolCallId = "c")),
            "c",
        )
        assertThat(result.single()).isInstanceOf(ToolAttachment.Image::class.java)
    }

    @Test
    fun `image filename routes to the image bucket without a mime type`() {
        val result = partitionToolCallAttachments(
            listOf(Attachment(fileId = "i", filename = "plot.png", toolCallId = "c")),
            "c",
        )
        assertThat(result.single()).isInstanceOf(ToolAttachment.Image::class.java)
    }

    @Test
    fun `image filepath alone routes to the image bucket`() {
        val result = partitionToolCallAttachments(
            listOf(Attachment(fileId = "i", filepath = "/api/files/i/render.WEBP", toolCallId = "c")),
            "c",
        )
        assertThat(result.single()).isInstanceOf(ToolAttachment.Image::class.java)
    }

    @Test
    fun `non image filename without a mime type stays a file chip`() {
        val result = partitionToolCallAttachments(
            listOf(Attachment(fileId = "d", filename = "data.bin", toolCallId = "c")),
            "c",
        )
        assertThat(result.single()).isInstanceOf(ToolAttachment.File::class.java)
    }

    // Guards the deliberate `svg` exclusion from IMAGE_EXTENSIONS: widening that list to be
    // "complete" reroutes a type-less .svg out of the artifact path and into an image preview.
    @Test
    fun `type-less svg is unaffected by the filename image test`() {
        val result = partitionToolCallAttachments(
            listOf(Attachment(fileId = "s", filename = "chart.svg", text = "<svg/>", toolCallId = "c")),
            "c",
        )
        assertThat(result.single()).isInstanceOf(ToolAttachment.File::class.java)
    }

    // ─── bucketToolAttachments ─────────────────────────────────────

    @Test
    fun `buckets render files first and images last`() {
        val items = listOf<ToolAttachment>(
            ToolAttachment.Image(Attachment(fileId = "i", filename = "a.png", type = "image/png")),
            ToolAttachment.File(Attachment(fileId = "f", filename = "a.bin")),
            ToolAttachment.Pdf(Attachment(fileId = "p", filename = "a.pdf", type = "application/pdf")),
            ToolAttachment.ArtifactContent(
                Attachment(fileId = "a", filename = "a.md", text = "# hi"),
                attachmentToArtifact(Attachment(fileId = "a", filename = "a.md", text = "# hi"))!!,
            ),
        )

        val buckets = bucketToolAttachments(items)

        assertThat(buckets.map { it::class.java }).containsExactly(
            ToolAttachmentBucket.Files::class.java,
            ToolAttachmentBucket.Artifacts::class.java,
            ToolAttachmentBucket.Pdfs::class.java,
            ToolAttachmentBucket.Images::class.java,
        ).inOrder()
    }

    @Test
    fun `empty buckets are omitted`() {
        val buckets = bucketToolAttachments(
            listOf(ToolAttachment.Image(Attachment(fileId = "i", type = "image/png"))),
        )
        assertThat(buckets).hasSize(1)
        assertThat(buckets.single()).isInstanceOf(ToolAttachmentBucket.Images::class.java)
    }

    @Test
    fun `zero byte entries sink within their bucket and the rest keep arrival order`() {
        val items = listOf<ToolAttachment>(
            ToolAttachment.File(Attachment(fileId = "empty", filename = "empty.csv", bytes = 0L)),
            ToolAttachment.File(Attachment(fileId = "real", filename = "real.csv", bytes = 42L)),
            ToolAttachment.File(Attachment(fileId = "unreported", filename = "unreported.csv")),
        )

        val files = (bucketToolAttachments(items).single() as ToolAttachmentBucket.Files).items

        assertThat(files.map { it.attachment.fileId })
            .containsExactly("real", "unreported", "empty").inOrder()
    }

    // ─── collectGroupAttachments ───────────────────────────────────

    @Test
    fun `group attachments flatten across tool calls in id order`() {
        val attachments = listOf(
            Attachment(fileId = "b", filename = "b.png", type = "image/png", toolCallId = "call_2"),
            Attachment(fileId = "a", filename = "a.png", type = "image/png", toolCallId = "call_1"),
        )

        val buckets = collectGroupAttachments(attachments, listOf("call_1", "call_2"))

        val images = (buckets.single() as ToolAttachmentBucket.Images).items
        assertThat(images.map { it.attachment.fileId }).containsExactly("a", "b").inOrder()
    }

    @Test
    fun `group attachments dedupe by file id across calls`() {
        val attachments = listOf(
            Attachment(fileId = "shared", filename = "out.png", type = "image/png", toolCallId = "call_1"),
            Attachment(fileId = "shared", filename = "out.png", type = "image/png", toolCallId = "call_2"),
        )

        val buckets = collectGroupAttachments(attachments, listOf("call_1", "call_2"))

        assertThat((buckets.single() as ToolAttachmentBucket.Images).items).hasSize(1)
    }

    @Test
    fun `group attachments keep same named files from different calls when file id is absent`() {
        val attachments = listOf(
            Attachment(filename = "chart.png", type = "image/png", toolCallId = "call_1"),
            Attachment(filename = "chart.png", type = "image/png", toolCallId = "call_2"),
        )

        val buckets = collectGroupAttachments(attachments, listOf("call_1", "call_2"))

        assertThat((buckets.single() as ToolAttachmentBucket.Images).items).hasSize(2)
    }

    @Test
    fun `empty tool call id list yields nothing`() {
        val attachments = listOf(
            Attachment(fileId = "a", filename = "a.png", type = "image/png", toolCallId = "call_1"),
        )
        assertThat(collectGroupAttachments(attachments, emptyList())).isEmpty()
    }

    @Test
    fun `office preview attachments stay out of the group list`() {
        val attachments = listOf(
            Attachment(
                fileId = "doc",
                filename = "report.docx",
                type = ArtifactType.DEFAULT_OFFICE_PREVIEW_MIME,
                text = "<p>report</p>",
                toolCallId = "call_1",
            ),
        )
        assertThat(collectGroupAttachments(attachments, listOf("call_1"))).isEmpty()
    }

    @Test
    fun `web search sources stay out of the group list`() {
        val attachments = listOf(Attachment(fileId = "w", type = "web_search", toolCallId = "call_1"))
        assertThat(collectGroupAttachments(attachments, listOf("call_1"))).isEmpty()
    }
}
