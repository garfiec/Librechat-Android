package com.garfiec.librechat.shared

import co.touchlab.kermit.Logger
import com.garfiec.librechat.core.logging.PersistentLogWriter
import platform.Foundation.NSException
import platform.Foundation.NSLog
import kotlin.experimental.ExperimentalNativeApi

private val log = Logger.withTag("CrashReporting")

/**
 * Installs a Kotlin/Native unhandled exception hook that:
 * 1. Writes a synchronous crash record via [persistentWriter] (best-effort; the process may die
 *    before any async log drain runs, so this path is intentionally synchronous).
 * 2. Logs the full Kotlin stack trace via Kermit + NSLog (visible in Xcode/Console.app)
 * 3. Wraps the Kotlin exception as an NSException with readable class name, message,
 *    and stack trace — so iOS crash logs show meaningful Kotlin context instead of
 *    just "Kotlin_ObjCExport_trapOnUndeclaredException" with a SIGABRT.
 *
 * Call once during app startup. [persistentWriter] may be null if Koin failed to start or the
 * writer could not be resolved — the hook is still installed for stack-trace/NSException behavior.
 */
@OptIn(ExperimentalNativeApi::class)
fun installCrashReporting(persistentWriter: PersistentLogWriter? = null) {
    setUnhandledExceptionHook { throwable ->
        // Persist a crash record first, synchronously, before the process tears down. Guarded so a
        // logging failure can never mask the original crash or recurse.
        runCatching {
            persistentWriter?.writeCrashRecord(
                tag = "Crash",
                message = "unhandled Kotlin exception",
                throwable = throwable,
            )
        }

        // Log the full Kotlin stack trace so it's captured in OS logs
        val stackTrace = throwable.stackTraceToString()
        val exceptionName = throwable::class.qualifiedName ?: throwable::class.simpleName ?: "Unknown"
        val message = throwable.message ?: "(no message)"

        log.e { "FATAL: Unhandled Kotlin exception: $exceptionName: $message" }
        log.e { stackTrace }

        // Also write directly to NSLog in case Kermit writers aren't flushed
        NSLog("FATAL: Unhandled Kotlin exception: %@: %@", exceptionName, message)
        NSLog("Stack trace:\n%@", stackTrace)

        // Raise as NSException so iOS crash reporters capture a meaningful report
        // instead of a bare SIGABRT with no context
        NSException(
            name = "KotlinException: $exceptionName",
            reason = message,
            userInfo = null,
        ).raise()
    }
    log.d { "iOS crash reporting hook installed" }
}
