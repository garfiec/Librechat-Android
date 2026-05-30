package com.garfiec.librechat.core.logging.io

import java.io.File
import java.io.FileOutputStream

internal class AndroidLogFile(path: String) : LogFileHandle {
    private val file = File(path)

    override fun ensureParentDir() {
        file.parentFile?.let { if (!it.exists()) it.mkdirs() }
    }

    override fun exists(): Boolean = file.exists()

    override fun appendLine(text: String) {
        // append=true → O_APPEND; each write is atomic up to PIPE_BUF, so the rare crash-time
        // concurrent write at worst interleaves whole small lines (the reader tolerates that).
        FileOutputStream(file, true).use { it.write((text + "\n").encodeToByteArray()) }
    }

    override fun sizeBytes(): Long = if (file.exists()) file.length() else 0L

    override fun readText(): String = if (file.exists()) file.readText(Charsets.UTF_8) else ""

    override fun delete() {
        if (file.exists()) file.delete()
    }

    override fun renameTo(targetPath: String) {
        file.renameTo(File(targetPath))
    }

    override fun lastModifiedMillis(): Long = if (file.exists()) file.lastModified() else 0L
}

internal actual fun openLogFile(path: String): LogFileHandle = AndroidLogFile(path)
