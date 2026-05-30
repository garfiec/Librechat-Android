package com.garfiec.librechat.core.logging.io

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSFileHandle
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate
import platform.Foundation.NSFileSize
import platform.Foundation.NSNumber
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.fileHandleForWritingAtPath
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.writeToFile
import platform.posix.memcpy

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal class IosLogFile(private val path: String) : LogFileHandle {
    private val fm get() = NSFileManager.defaultManager

    override fun ensureParentDir() {
        val parent = path.substringBeforeLast('/', "")
        if (parent.isNotEmpty() && !fm.fileExistsAtPath(parent)) {
            fm.createDirectoryAtPath(parent, withIntermediateDirectories = true, attributes = null, error = null)
        }
    }

    override fun exists(): Boolean = fm.fileExistsAtPath(path)

    override fun appendLine(text: String) {
        val data = toNSData(text + "\n") ?: return
        if (!fm.fileExistsAtPath(path)) {
            data.writeToFile(path, atomically = false)
            return
        }
        val handle = NSFileHandle.fileHandleForWritingAtPath(path) ?: return
        handle.seekToEndReturningOffset(null, null)
        handle.writeData(data, null)
        handle.closeAndReturnError(null)
    }

    override fun sizeBytes(): Long {
        val attrs = fm.attributesOfItemAtPath(path, null) ?: return 0L
        val size = attrs[NSFileSize] as? NSNumber ?: return 0L
        return size.longLongValue
    }

    override fun readText(): String {
        // Read raw bytes and decode lossily (invalid sequences → U+FFFD) rather than
        // NSString.stringWithContentsOfFile, which returns nil for the WHOLE file on a single bad
        // byte — a partial/interleaved write at the tail would otherwise drop the entire segment.
        val data = NSData.dataWithContentsOfFile(path) ?: return ""
        val length = data.length.toInt()
        if (length == 0) return ""
        val bytes = ByteArray(length)
        bytes.usePinned { pinned -> memcpy(pinned.addressOf(0), data.bytes, data.length) }
        return bytes.decodeToString()
    }

    override fun delete() {
        if (fm.fileExistsAtPath(path)) fm.removeItemAtPath(path, null)
    }

    override fun renameTo(targetPath: String) {
        if (fm.fileExistsAtPath(targetPath)) fm.removeItemAtPath(targetPath, null)
        fm.moveItemAtPath(path, targetPath, null)
    }

    override fun lastModifiedMillis(): Long {
        val attrs = fm.attributesOfItemAtPath(path, null) ?: return 0L
        val date = attrs[NSFileModificationDate] as? NSDate ?: return 0L
        return (date.timeIntervalSince1970 * 1000).toLong()
    }

    private fun toNSData(text: String): NSData? {
        val bytes = text.encodeToByteArray()
        if (bytes.isEmpty()) return null
        return bytes.usePinned { pinned ->
            NSData.create(bytes = pinned.addressOf(0), length = bytes.size.toULong())
        }
    }
}

internal actual fun openLogFile(path: String): LogFileHandle = IosLogFile(path)
