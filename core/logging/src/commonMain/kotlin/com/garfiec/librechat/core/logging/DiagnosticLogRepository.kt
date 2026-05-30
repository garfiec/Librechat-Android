package com.garfiec.librechat.core.logging

import com.garfiec.librechat.core.logging.io.LogSink
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Read/clear access to the persistent diagnostic buffer, for the Settings → Data export/clear UI.
 * On-disk content is already redacted at write time, so [exportText] returns it directly.
 */
interface DiagnosticLogRepository {
    /** Full buffer (previous ++ active segment) as JSONL text. */
    suspend fun exportText(): String

    /** Same content as UTF-8 bytes, for the platform share sheet. */
    suspend fun exportBytes(): ByteArray

    /** Deletes both segments. */
    suspend fun clear()

    /** Current on-disk buffer size in bytes (for the "Clear logs" affordance). */
    suspend fun bufferSizeBytes(): Long
}

internal class DiagnosticLogRepositoryImpl(
    private val sink: LogSink,
    private val dispatcher: CoroutineDispatcher,
) : DiagnosticLogRepository {

    // All operations run on the same single-thread dispatcher as the writer's drain, so reads/clears
    // never race in-flight appends or rotation.
    override suspend fun exportText(): String = withContext(dispatcher) { sink.readAll() }

    override suspend fun exportBytes(): ByteArray = withContext(dispatcher) { sink.readAll().encodeToByteArray() }

    override suspend fun clear() = withContext(dispatcher) { sink.clear() }

    override suspend fun bufferSizeBytes(): Long = withContext(dispatcher) { sink.sizeBytes() }
}
