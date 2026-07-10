package com.garfiec.librechat.shared

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import com.garfiec.librechat.core.common.AppInfo
import com.garfiec.librechat.core.logging.PersistentLogWriter
import com.garfiec.librechat.core.logging.PlatformInfo
import com.garfiec.librechat.core.logging.logStartupHeader
import com.garfiec.librechat.core.logging.startMainThreadWatchdog
import kotlinx.coroutines.CoroutineExceptionHandler
import org.koin.core.context.startKoin
import platform.Foundation.NSLog

/**
 * LogWriter that routes Kermit output through NSLog so it appears in
 * the unified OS log (visible via `log stream` / Console.app / Xcode).
 */
private class IosNSLogWriter : LogWriter() {
    override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
        val level = severity.name.first()
        NSLog("[$level/$tag] $message")
        throwable?.let { NSLog("[$level/$tag] ${it.stackTraceToString()}") }
    }
}

/**
 * Entry point for iOS to initialize Koin DI.
 * Call from Swift: IosKoinHelperKt.startIosKoin()
 */
fun startIosKoin() {
    // Route Kermit logs through NSLog for OS log visibility. The persistent file writer is added
    // after Koin starts (below), once its dependencies are resolvable.
    Logger.setLogWriters(IosNSLogWriter())
    Logger.setMinSeverity(Severity.Debug)
    Logger.withTag("Koin").d { "startIosKoin: initializing Koin DI" }

    // Install a writer-less crash hook BEFORE startKoin so a DI-init failure (the most crash-prone
    // phase of launch) still surfaces a readable NSException + Kotlin stack instead of an opaque
    // SIGABRT. It's upgraded with the persistent writer once Koin can supply it (below).
    runCatching { installCrashReporting() }

    val app = startKoin {
        modules(iosSharedModule)
    }
    IosKoinAccessor.koin = app.koin
    Logger.withTag("Koin").d { "startIosKoin: Koin initialized successfully" }

    // Wire diagnostic logging now that Koin can supply the writer + platform info. All of this is
    // best-effort: a logging-setup failure must never prevent the app from launching.
    runCatching {
        val writer: PersistentLogWriter = app.koin.get()

        // Keep NSLog visibility AND add the persistent file sink.
        Logger.setLogWriters(IosNSLogWriter(), writer)

        // Install the Kotlin/Native crash hook with the writer so unhandled exceptions are persisted
        // synchronously before the process dies.
        installCrashReporting(writer)

        val appInfo: AppInfo = app.koin.get()
        val platformInfo: PlatformInfo = app.koin.get()
        logStartupHeader(appInfo = appInfo, platformInfo = platformInfo)

        // The watchdog owns its own isolated supervised scope; hand it the diagnostic exception
        // handler so escaped failures reach the crash-record path (and can't poison ApplicationScope).
        val exceptionHandler: CoroutineExceptionHandler = app.koin.get()
        startMainThreadWatchdog(exceptionHandler)
    }.onFailure {
        // Fall back to a crash hook with no writer so we still get readable NSException reports.
        runCatching { installCrashReporting() }
        Logger.withTag("Koin").e(it) { "Diagnostic logging init failed (non-fatal)" }
    }
}
