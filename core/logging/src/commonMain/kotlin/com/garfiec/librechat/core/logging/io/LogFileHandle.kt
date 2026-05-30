package com.garfiec.librechat.core.logging.io

/**
 * Minimal file primitive the log sink needs. Backed by `java.io.File` on Android and
 * `NSFileManager`/`NSFileHandle` on iOS. Modeled as an interface (not an `expect class`) so the
 * rotation logic in [LogSink] can be unit-tested in commonMain against an in-memory fake.
 */
internal interface LogFileHandle {
    fun ensureParentDir()
    fun exists(): Boolean

    /** Appends `text` plus a trailing newline. Uses append-mode (O_APPEND-style) writes. */
    fun appendLine(text: String)

    fun sizeBytes(): Long
    fun readText(): String
    fun delete()

    /** Moves this file to [targetPath], replacing any existing file there. */
    fun renameTo(targetPath: String)

    fun lastModifiedMillis(): Long
}

/** Opens a platform-backed handle for [path]. The file need not exist yet. */
internal expect fun openLogFile(path: String): LogFileHandle
