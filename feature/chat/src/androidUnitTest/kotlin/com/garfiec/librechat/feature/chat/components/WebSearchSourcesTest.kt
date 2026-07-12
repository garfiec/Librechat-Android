package com.garfiec.librechat.feature.chat.components

import com.garfiec.librechat.core.model.Attachment
import com.garfiec.librechat.core.model.WebSearchData
import com.garfiec.librechat.core.model.WebSearchSource
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WebSearchSourcesTest {

    private fun webAttachment(
        toolCallId: String?,
        organic: List<WebSearchSource> = emptyList(),
        topStories: List<WebSearchSource> = emptyList(),
    ) = Attachment(
        type = "web_search",
        toolCallId = toolCallId,
        webSearch = WebSearchData(organic = organic, topStories = topStories),
    )

    @Test
    fun `collects organic then top-story sources for the matching tool call`() {
        val attachments = listOf(
            webAttachment(
                toolCallId = "c1",
                organic = listOf(WebSearchSource(link = "https://a.com/x", title = "A")),
                topStories = listOf(WebSearchSource(link = "https://b.com/y", title = "B")),
            ),
        )

        val results = collectWebSearchSources(attachments, "c1")

        assertThat(results.map { it.url }).containsExactly("https://a.com/x", "https://b.com/y").inOrder()
        assertThat(results.first().title).isEqualTo("A")
    }

    @Test
    fun `dedups repeated sources by link across accumulating attachments`() {
        // Streaming re-emits the attachment once per processed source, each a superset.
        val attachments = listOf(
            webAttachment("c1", organic = listOf(WebSearchSource(link = "https://a.com", title = "A"))),
            webAttachment(
                "c1",
                organic = listOf(
                    WebSearchSource(link = "https://a.com", title = "A"),
                    WebSearchSource(link = "https://b.com", title = "B"),
                ),
            ),
        )

        val results = collectWebSearchSources(attachments, "c1")

        assertThat(results.map { it.url }).containsExactly("https://a.com", "https://b.com").inOrder()
    }

    @Test
    fun `excludes attachments belonging to a different tool call`() {
        // A second search turn (its own toolCallId) must not duplicate its sources here.
        val attachments = listOf(
            webAttachment("other", organic = listOf(WebSearchSource(link = "https://a.com", title = "A"))),
        )
        assertThat(collectWebSearchSources(attachments, "c1")).isEmpty()
    }

    @Test
    fun `id-less attachments attach to any search call`() {
        // Older payloads didn't always set a toolCallId — attach them best-effort.
        val attachments = listOf(
            webAttachment(toolCallId = null, organic = listOf(WebSearchSource(link = "https://a.com", title = "A"))),
        )
        assertThat(collectWebSearchSources(attachments, "c1").map { it.url })
            .containsExactly("https://a.com")
    }

    @Test
    fun `falls back to all attachments when the tool call itself has no id`() {
        val attachments = listOf(
            webAttachment("other", organic = listOf(WebSearchSource(link = "https://a.com", title = "A"))),
        )
        assertThat(collectWebSearchSources(attachments, null).map { it.url })
            .containsExactly("https://a.com")
    }

    @Test
    fun `ignores non web-search attachments and sources without a link`() {
        val attachments = listOf(
            Attachment(fileId = "f", filename = "x.pdf", type = "application/pdf", toolCallId = "c1"),
            webAttachment("c1", organic = listOf(WebSearchSource(link = null, title = "no link"))),
        )
        assertThat(collectWebSearchSources(attachments, "c1")).isEmpty()
    }

    @Test
    fun `titles missing on a source fall back to the host`() {
        val attachments = listOf(
            webAttachment("c1", organic = listOf(WebSearchSource(link = "https://www.example.com/page", title = null))),
        )
        assertThat(collectWebSearchSources(attachments, "c1").single().title).isEqualTo("example.com")
    }

    @Test
    fun `hostOf strips scheme port path and www`() {
        assertThat(hostOf("https://www.example.com:8080/a/b?q=1")).isEqualTo("example.com")
        assertThat(hostOf("http://en.wikipedia.org/wiki/X")).isEqualTo("en.wikipedia.org")
        assertThat(hostOf("example.com")).isEqualTo("example.com")
    }
}
