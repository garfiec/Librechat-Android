package com.garfiec.librechat.core.logging.io

import com.garfiec.librechat.core.logging.LogConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LogSinkRotationTest {

    /** In-memory file system shared across handles by path, so renameTo moves content correctly. */
    private class FakeFs {
        val files = mutableMapOf<String, StringBuilder>()

        fun handle(path: String): LogFileHandle = object : LogFileHandle {
            override fun ensureParentDir() {}
            override fun exists() = files.containsKey(path)
            override fun appendLine(text: String) {
                files.getOrPut(path) { StringBuilder() }.append(text).append("\n")
            }
            override fun sizeBytes() = (files[path]?.length ?: 0).toLong()
            override fun readText() = files[path]?.toString() ?: ""
            override fun delete() { files.remove(path) }
            override fun renameTo(targetPath: String) {
                val content = files.remove(path)
                if (content != null) files[targetPath] = content else files.remove(targetPath)
            }
            override fun lastModifiedMillis() = 0L
        }
    }

    // 5-char lines → 6 bytes each ("\n"); segment cap 10 bytes → rotates every 2 lines.
    private val config = LogConfig(totalMaxBytes = 20, maxAgeMillis = Long.MAX_VALUE)

    @Test
    fun buffer_is_bounded_to_two_segments_keeping_most_recent() {
        val fs = FakeFs()
        val sink = LogSink(dir = "/d", config = config, fileFactory = fs::handle)

        listOf("line1", "line2", "line3", "line4", "line5", "line6").forEach { sink.append(it) }

        val all = sink.readAll()
        assertEquals("line5\nline6\n", all, "only the most recent segment(s) survive: '$all'")
        assertFalse(all.contains("line1"), "oldest lines must be dropped once bounded")
        assertTrue(sink.sizeBytes() <= config.totalMaxBytes, "total stays within the cap")
    }

    @Test
    fun export_concatenates_previous_then_active_in_order() {
        val fs = FakeFs()
        val sink = LogSink(dir = "/d", config = config, fileFactory = fs::handle)

        // line1,line2 fill active → rotate to previous; line3 lands in fresh active.
        sink.append("line1")
        sink.append("line2")
        sink.append("line3")

        assertEquals("line1\nline2\nline3\n", sink.readAll())
    }

    @Test
    fun clear_empties_the_buffer() {
        val fs = FakeFs()
        val sink = LogSink(dir = "/d", config = config, fileFactory = fs::handle)
        listOf("line1", "line2", "line3").forEach { sink.append(it) }

        sink.clear()

        assertEquals("", sink.readAll())
        assertEquals(0L, sink.sizeBytes())
    }
}
