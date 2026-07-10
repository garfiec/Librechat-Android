package com.garfiec.librechat

import android.app.Application
import co.touchlab.kermit.Logger
import co.touchlab.kermit.platformLogWriter
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.request.crossfade
import coil3.svg.SvgDecoder
import com.garfiec.librechat.core.common.AppInfo
import com.garfiec.librechat.core.logging.PersistentLogWriter
import com.garfiec.librechat.core.logging.PlatformInfo
import com.garfiec.librechat.core.logging.logStartupHeader
import com.garfiec.librechat.core.logging.startMainThreadWatchdog
import com.garfiec.librechat.shared.di.sharedKoinModules
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineExceptionHandler
import org.koin.android.ext.android.inject
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.koin.core.logger.Level

class LibreChatApplication : Application(), SingletonImageLoader.Factory {

    private val httpClient: HttpClient by inject()

    override fun onCreate() {
        super.onCreate()
        try {
            startKoin {
                if (BuildConfig.DEBUG) {
                    androidLogger(Level.DEBUG)
                }
                androidContext(this@LibreChatApplication)
                allowOverride(false)
                modules(sharedKoinModules)
            }
        } catch (e: Exception) {
            Logger.e(e) { "Koin initialization failed" }
            throw e // Always rethrow — DI failure is unrecoverable
        }

        // Diagnostic logging is wired AFTER Koin so the writer's dependencies are resolvable.
        // Everything here is best-effort: a logging-setup failure must never block app launch.
        runCatching { initDiagnostics() }
            .onFailure { Logger.e(it) { "Diagnostic logging init failed (non-fatal)" } }
    }

    private fun initDiagnostics() {
        val writer: PersistentLogWriter by inject()

        // Preserve Logcat (platformLogWriter) and add the persistent file sink alongside it.
        Logger.setLogWriters(platformLogWriter(), writer)

        // Capture uncaught exceptions on any thread: write a synchronous crash record, then delegate
        // to the previous handler so the process still crashes exactly as it would have.
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                writer.writeCrashRecord(
                    tag = "Crash",
                    message = "uncaught exception on thread ${thread.name}",
                    throwable = throwable,
                )
            }
            previous?.uncaughtException(thread, throwable)
        }

        // Emit the startup header. detectedBackendVersion is null at cold start (config not yet
        // fetched); a later config-load path snapshots the detected version separately.
        val appInfo: AppInfo by inject()
        val platformInfo: PlatformInfo by inject()
        logStartupHeader(appInfo = appInfo, platformInfo = platformInfo)

        // The watchdog owns its own supervised scope; we just hand it the diagnostic exception
        // handler so any escaped failure is funneled into the crash-record path.
        val exceptionHandler: CoroutineExceptionHandler by inject()
        startMainThreadWatchdog(exceptionHandler)
    }

    override fun newImageLoader(context: coil3.PlatformContext): ImageLoader {
        return ImageLoader.Builder(context)
            .components {
                add(KtorNetworkFetcherFactory(httpClient))
                add(SvgDecoder.Factory())
            }
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(context, 0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(250L * 1024 * 1024) // 250 MB
                    .build()
            }
            .crossfade(true)
            .build()
    }
}
