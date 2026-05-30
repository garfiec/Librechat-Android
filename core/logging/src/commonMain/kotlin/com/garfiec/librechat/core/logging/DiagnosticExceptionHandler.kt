package com.garfiec.librechat.core.logging

import kotlinx.coroutines.CoroutineExceptionHandler

/**
 * Builds a [CoroutineExceptionHandler] that records uncaught coroutine failures through the
 * synchronous crash path of [writer] before they are swallowed by the scope's supervisor.
 *
 * Opt-in: attach it to a scope's context (e.g. the watchdog scope, or any app-lifetime scope that
 * wants its escaped exceptions captured). The handler is best-effort and never rethrows — a failure
 * inside logging must not escalate into a crash loop.
 */
fun diagnosticCoroutineExceptionHandler(writer: PersistentLogWriter): CoroutineExceptionHandler =
    CoroutineExceptionHandler { context, throwable ->
        runCatching {
            writer.writeCrashRecord(
                tag = "Crash",
                message = "uncaught coroutine exception in $context",
                throwable = throwable,
            )
        }
    }
