package com.garfiec.librechat.core.logging.di

import com.garfiec.librechat.core.logging.IosPlatformInfo
import com.garfiec.librechat.core.logging.PlatformInfo
import com.garfiec.librechat.core.logging.io.LogDirProvider
import kotlinx.cinterop.ExperimentalForeignApi
import org.koin.core.module.Module
import org.koin.dsl.module
import platform.Foundation.NSFileManager
import platform.Foundation.NSHomeDirectory

@OptIn(ExperimentalForeignApi::class)
private class IosLogDirProvider : LogDirProvider {
    // Application Support persists across restarts (unlike Caches, which the OS can evict).
    override fun logDir(): String {
        val dir = NSHomeDirectory() + "/Library/Application Support/diag_logs"
        NSFileManager.defaultManager.createDirectoryAtPath(
            dir,
            withIntermediateDirectories = true,
            attributes = null,
            error = null,
        )
        return dir
    }
}

actual val loggingPlatformModule: Module = module {
    single<LogDirProvider> { IosLogDirProvider() }
    single<PlatformInfo> { IosPlatformInfo() }
}
