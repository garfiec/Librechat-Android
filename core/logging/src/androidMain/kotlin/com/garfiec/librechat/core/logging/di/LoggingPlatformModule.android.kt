package com.garfiec.librechat.core.logging.di

import android.content.Context
import com.garfiec.librechat.core.logging.AndroidPlatformInfo
import com.garfiec.librechat.core.logging.PlatformInfo
import com.garfiec.librechat.core.logging.io.LogDirProvider
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module
import java.io.File

private class AndroidLogDirProvider(private val context: Context) : LogDirProvider {
    // filesDir is app-private and persists across restarts (unlike cacheDir, which the OS can evict).
    override fun logDir(): String = File(context.filesDir, "diag_logs").absolutePath
}

actual val loggingPlatformModule: Module = module {
    single<LogDirProvider> { AndroidLogDirProvider(androidContext()) }
    single<PlatformInfo> { AndroidPlatformInfo() }
}
