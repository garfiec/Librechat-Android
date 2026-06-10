package com.garfiec.librechat.core.ui.media

import java.io.File

/**
 * Deletes files in [dir] last modified more than [olderThanMs] ago.
 *
 * Used to clean up the temp files written for the system share sheet (image share, artifact share)
 * without a fixed timer: the next share sweeps the previous one once it is safely past any in-flight
 * read by the chooser/target app. Callers pick their own threshold for the content they write.
 */
fun sweepStaleFiles(dir: File, olderThanMs: Long) {
    val staleThreshold = System.currentTimeMillis() - olderThanMs
    dir.listFiles()
        ?.filter { it.lastModified() < staleThreshold }
        ?.forEach { it.delete() }
}
