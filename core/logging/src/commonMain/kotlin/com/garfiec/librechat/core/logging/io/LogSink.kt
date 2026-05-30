package com.garfiec.librechat.core.logging.io

import com.garfiec.librechat.core.logging.LogConfig
import kotlin.time.Clock

/**
 * Bounded, line-oriented ring buffer backed by two on-disk segments:
 *   - `diag.log`   — active segment, currently being appended to
 *   - `diag.1.log` — previous (rotated) segment
 *
 * When the active segment reaches [LogConfig.segmentCapBytes], the previous segment is deleted, the
 * active is renamed to previous, and a fresh active is started. Export = previous ++ active, so the
 * buffer always holds between `totalMaxBytes/2` and `totalMaxBytes` of the most recent JSONL.
 *
 * Not internally synchronized: the normal append path is driven by a single drain coroutine (see
 * PersistentLogWriter), so there is exactly one writer. [appendBlocking] is the only exception — a
 * rare, best-effort crash-time write that may race the drain; line-oriented JSONL plus a
 * partial-line-tolerant reader make an occasional interleave harmless. Every method swallows I/O
 * failures (disk full, missing dir) so logging can never crash the app.
 */
internal class LogSink(
    private val dir: String,
    private val config: LogConfig,
    private val fileFactory: (String) -> LogFileHandle = ::openLogFile,
) {
    private val activePath = "$dir/diag.log"
    private val previousPath = "$dir/diag.1.log"
    private val active = fileFactory(activePath)
    private val previous = fileFactory(previousPath)

    /**
     * Running size of the active segment, maintained in memory so the hot append path never stats
     * the file. Seeded once from disk (a prior run's active segment may carry over). Only the single
     * drain coroutine mutates it via [append]/[rotate]/[clear]; [appendBlocking] (rare crash path)
     * intentionally leaves it untouched — the bounded drift is at most one record.
     */
    private var activeBytes: Long = runCatching {
        if (active.exists()) active.sizeBytes() else 0L
    }.getOrDefault(0L)

    init {
        runCatching { active.ensureParentDir() }
        pruneByAge()
    }

    /** Normal append path. Appends one JSONL line and rotates if the active segment is full. */
    fun append(line: String) {
        runCatching {
            active.ensureParentDir()
            active.appendLine(line)
            activeBytes += line.encodeToByteArray().size + 1 // +1 for the trailing newline
            if (activeBytes >= config.segmentCapBytes) rotate()
        }
    }

    /** Crash-time append: writes synchronously, skips rotation, never throws. */
    fun appendBlocking(line: String) {
        runCatching {
            active.ensureParentDir()
            active.appendLine(line)
        }
    }

    fun readAll(): String {
        val prev = runCatching { if (previous.exists()) previous.readText() else "" }.getOrDefault("")
        val cur = runCatching { if (active.exists()) active.readText() else "" }.getOrDefault("")
        return prev + cur
    }

    fun sizeBytes(): Long = runCatching {
        (if (previous.exists()) previous.sizeBytes() else 0L) +
            (if (active.exists()) active.sizeBytes() else 0L)
    }.getOrDefault(0L)

    fun clear() {
        runCatching {
            if (active.exists()) active.delete()
            if (previous.exists()) previous.delete()
            active.ensureParentDir()
            activeBytes = 0L
        }
    }

    private fun rotate() {
        runCatching {
            if (previous.exists()) previous.delete()
            active.renameTo(previousPath)
            activeBytes = 0L // fresh active segment starts empty
        }
    }

    /** Drops a stale previous segment on startup so the buffer also respects an age cap. */
    private fun pruneByAge() {
        runCatching {
            if (previous.exists()) {
                val age = Clock.System.now().toEpochMilliseconds() - previous.lastModifiedMillis()
                if (age > config.maxAgeMillis) previous.delete()
            }
        }
    }
}
