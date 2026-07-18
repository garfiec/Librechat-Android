package com.garfiec.librechat.core.ui.pdf

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device tests for the shared PDF rendering path ([PdfDocumentHolder] + [PdfPageContent])
 * against the real framework `PdfRenderer`, with locally generated documents — no server involved.
 */
@RunWith(AndroidJUnit4::class)
class PdfPageContentInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun holderOpensDocumentAndRendersFirstPage() {
        val holder = requireNotNull(PdfDocumentHolder.create(context, minimalPdf(pages = 3)))
        try {
            assertEquals(3, holder.pageCount)
            val bitmap = runBlocking { holder.renderPage(0, widthPx = 500) }
            assertNotNull(bitmap)
            val expected = 612f / 792f
            assertEquals(expected, bitmap!!.width.toFloat() / bitmap.height, 0.01f)
            assertEquals(expected, holder.aspectRatio(0), 0.01f)
        } finally {
            holder.close()
        }
    }

    @Test
    fun pageCountIsCappedForPathologicalDocuments() {
        val holder = requireNotNull(PdfDocumentHolder.create(context, minimalPdf(pages = 2001)))
        try {
            assertEquals(2000, holder.pageCount)
        } finally {
            holder.close()
        }
    }

    @Test
    fun scrollingThroughListRendersPagesOnDemandAndBack() {
        val holder = requireNotNull(PdfDocumentHolder.create(context, minimalPdf(pages = 40)))
        composeRule.setContent {
            LazyColumn(modifier = Modifier.fillMaxSize().testTag("pdfList")) {
                items(count = holder.pageCount, key = { it }) { index ->
                    PdfPageContent(
                        doc = holder,
                        index = index,
                        contentDescription = "page-${index + 1}",
                    )
                }
            }
        }
        try {
            // The final index revisits page 1 after its bitmap was recycled on scroll-out.
            for (target in intArrayOf(0, 12, 25, 39, 0)) {
                composeRule.onNodeWithTag("pdfList").performScrollToIndex(target)
                composeRule.waitUntilPageRendered(target + 1)
            }
        } finally {
            holder.close()
        }
    }

    @Test
    fun widthChangeReRendersVisiblePageWithoutCrash() {
        val holder = requireNotNull(PdfDocumentHolder.create(context, minimalPdf(pages = 2)))
        val narrow = mutableStateOf(false)
        composeRule.setContent {
            val isNarrow by narrow
            Box(modifier = Modifier.width(if (isNarrow) 200.dp else 360.dp)) {
                PdfPageContent(doc = holder, index = 0, contentDescription = "page-1")
            }
        }
        try {
            composeRule.waitUntilPageRendered(1)
            // Each flip recycles the superseded bitmap; recycling a still-displayed one would
            // crash the draw pass.
            repeat(3) {
                composeRule.runOnUiThread { narrow.value = !narrow.value }
                composeRule.waitForIdle()
                composeRule.waitUntilPageRendered(1)
            }
        } finally {
            holder.close()
        }
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.waitUntilPageRendered(
        pageNumber: Int,
    ) {
        waitUntil(timeoutMillis = 10_000) {
            onAllNodesWithContentDescription("page-$pageNumber").fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** Builds a minimal valid multi-page PDF (US-Letter, one text line + block per page) in memory. */
    private fun minimalPdf(pages: Int): ByteArray {
        val objects = ArrayList<Pair<Int, ByteArray>>(2 * pages + 3)
        val kids = (0 until pages).joinToString(" ") { "${4 + it} 0 R" }
        objects.add(1 to "<< /Type /Catalog /Pages 2 0 R >>".toByteArray())
        objects.add(2 to "<< /Type /Pages /Kids [$kids] /Count $pages >>".toByteArray())
        objects.add(3 to "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>".toByteArray())
        for (i in 0 until pages) {
            objects.add(
                4 + i to (
                    "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] " +
                        "/Resources << /Font << /F1 3 0 R >> >> /Contents ${4 + pages + i} 0 R >>"
                    ).toByteArray(),
            )
        }
        for (i in 0 until pages) {
            val stream = (
                "BT /F1 36 Tf 72 700 Td (PAGE ${i + 1} of $pages) Tj ET\n" +
                    "0.2 0.4 0.8 rg 72 300 468 300 re f"
                ).toByteArray()
            objects.add(
                4 + pages + i to (
                    "<< /Length ${stream.size} >>\nstream\n".toByteArray() +
                        stream + "\nendstream".toByteArray()
                    ),
            )
        }
        val out = java.io.ByteArrayOutputStream()
        out.write("%PDF-1.4\n".toByteArray())
        val offsets = IntArray(objects.size + 1)
        for ((num, body) in objects) {
            offsets[num] = out.size()
            out.write("$num 0 obj\n".toByteArray())
            out.write(body)
            out.write("\nendobj\n".toByteArray())
        }
        val xrefPos = out.size()
        out.write("xref\n0 ${objects.size + 1}\n".toByteArray())
        out.write("0000000000 65535 f \n".toByteArray())
        for (num in 1..objects.size) {
            out.write("%010d 00000 n \n".format(offsets[num]).toByteArray())
        }
        out.write(
            "trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xrefPos\n%%EOF\n"
                .toByteArray(),
        )
        return out.toByteArray()
    }
}
