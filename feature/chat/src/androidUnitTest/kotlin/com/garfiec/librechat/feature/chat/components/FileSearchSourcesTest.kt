package com.garfiec.librechat.feature.chat.components

import com.garfiec.librechat.core.model.Attachment
import com.garfiec.librechat.core.model.FileSearchData
import com.garfiec.librechat.core.model.FileSearchSource
import com.garfiec.librechat.core.model.FileSearchSourceMetadata
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FileSearchSourcesTest {

    private fun searchAttachment(
        toolCallId: String?,
        vararg sources: FileSearchSource,
    ) = Attachment(
        type = "file_search",
        toolCallId = toolCallId,
        fileSearch = FileSearchData(sources = sources.toList()),
    )

    @Test
    fun `orders citations most relevant first`() {
        val attachments = listOf(
            searchAttachment(
                "c1",
                FileSearchSource(fileId = "f1", fileName = "low.pdf", relevance = 0.3),
                FileSearchSource(fileId = "f2", fileName = "high.pdf", relevance = 0.9),
            ),
        )

        val citations = collectFileSearchSources(attachments, "c1")

        assertThat(citations.map { it.fileName }).containsExactly("high.pdf", "low.pdf").inOrder()
    }

    @Test
    fun `merges the chunks that came from one file`() {
        // The retriever returns one source per matched passage, so a single file arrives several
        // times; upstream folds them into one row rather than listing the file repeatedly.
        val attachments = listOf(
            searchAttachment(
                "c1",
                FileSearchSource(
                    fileId = "f1",
                    fileName = "notes.pdf",
                    relevance = 0.4,
                    content = "first",
                    pages = listOf(2),
                ),
                FileSearchSource(
                    fileId = "f1",
                    fileName = "notes.pdf",
                    relevance = 0.8,
                    content = "second",
                    pages = listOf(5),
                ),
            ),
        )

        val citations = collectFileSearchSources(attachments, "c1")

        assertThat(citations).hasSize(1)
        assertThat(citations.first().relevance).isEqualTo(0.8)
        assertThat(citations.first().content).isEqualTo("first\n\nsecond")
        assertThat(citations.first().pages).containsExactly(2, 5).inOrder()
    }

    @Test
    fun `orders one source's pages by their own relevance`() {
        val attachments = listOf(
            searchAttachment(
                "c1",
                FileSearchSource(
                    fileId = "f1",
                    fileName = "notes.pdf",
                    relevance = 0.8,
                    pages = listOf(2, 7, 4),
                    pageRelevance = mapOf("2" to 0.1, "7" to 0.9, "4" to 0.5),
                ),
            ),
        )

        val citations = collectFileSearchSources(attachments, "c1")

        assertThat(citations.first().pages).containsExactly(7, 4, 2).inOrder()
    }

    @Test
    fun `carries the file type through for the row icon`() {
        val attachments = listOf(
            searchAttachment(
                "c1",
                FileSearchSource(
                    fileId = "f1",
                    fileName = "sheet.csv",
                    relevance = 0.5,
                    metadata = FileSearchSourceMetadata(fileType = "text/csv"),
                ),
            ),
        )

        assertThat(collectFileSearchSources(attachments, "c1").first().fileType).isEqualTo("text/csv")
    }

    @Test
    fun `excludes attachments belonging to a different tool call`() {
        val attachments = listOf(
            searchAttachment("other", FileSearchSource(fileId = "f1", fileName = "a.pdf", relevance = 0.9)),
            searchAttachment("c1", FileSearchSource(fileId = "f2", fileName = "b.pdf", relevance = 0.5)),
        )

        val citations = collectFileSearchSources(attachments, "c1")

        assertThat(citations.map { it.fileName }).containsExactly("b.pdf")
    }

    @Test
    fun `keeps attachments carrying no tool call id`() {
        val attachments = listOf(
            searchAttachment(null, FileSearchSource(fileId = "f1", fileName = "a.pdf", relevance = 0.9)),
        )

        assertThat(collectFileSearchSources(attachments, "c1")).hasSize(1)
    }

    @Test
    fun `ignores web-search attachments`() {
        val attachments = listOf(
            Attachment(type = "web_search", toolCallId = "c1"),
            Attachment(fileId = "f", filename = "chart.png", type = "image/png", toolCallId = "c1"),
        )

        assertThat(collectFileSearchSources(attachments, "c1")).isEmpty()
    }

    @Test
    fun `routes file_search and retrieval away from the web-search card`() {
        assertThat(isFileSearchToolCall("file_search")).isTrue()
        assertThat(isFileSearchToolCall("retrieval")).isTrue()
        assertThat(isWebSearchToolCall("file_search")).isFalse()
        assertThat(isWebSearchToolCall("retrieval")).isFalse()
        assertThat(isFileSearchToolCall("web_search")).isFalse()
    }
}
