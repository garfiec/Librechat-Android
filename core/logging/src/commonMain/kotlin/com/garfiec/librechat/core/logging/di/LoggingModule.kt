package com.garfiec.librechat.core.logging.di

import com.garfiec.librechat.core.common.di.KoinQualifiers
import com.garfiec.librechat.core.logging.DiagnosticLogRepository
import com.garfiec.librechat.core.logging.DiagnosticLogRepositoryImpl
import com.garfiec.librechat.core.logging.LogConfig
import com.garfiec.librechat.core.logging.PersistentLogWriter
import com.garfiec.librechat.core.logging.currentThreadName
import com.garfiec.librechat.core.logging.diagnosticCoroutineExceptionHandler
import com.garfiec.librechat.core.logging.io.LogDirProvider
import com.garfiec.librechat.core.logging.io.LogSink
import com.garfiec.librechat.core.logging.redact.LogRedactor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.koin.core.module.Module
import org.koin.dsl.module

/** Provides the platform [LogDirProvider]. */
expect val loggingPlatformModule: Module

@OptIn(ExperimentalCoroutinesApi::class)
val loggingModule = module {
    includes(loggingPlatformModule)

    single { LogConfig() }
    single { LogRedactor() }

    // Dedicated single-thread dispatcher: all file appends/rotation/export happen here.
    single<CoroutineDispatcher>(KoinQualifiers.LogWriter) {
        get<CoroutineDispatcher>(KoinQualifiers.IO).limitedParallelism(1)
    }

    single { LogSink(dir = get<LogDirProvider>().logDir(), config = get()) }

    single {
        PersistentLogWriter(
            sink = get(),
            redactor = get(),
            threadName = ::currentThreadName,
            scope = get<CoroutineScope>(KoinQualifiers.ApplicationScope),
            dispatcher = get<CoroutineDispatcher>(KoinQualifiers.LogWriter),
            capacity = get<LogConfig>().channelCapacity,
            maxThrowableChars = get<LogConfig>().maxThrowableChars,
            maxCrashThrowableChars = get<LogConfig>().maxCrashThrowableChars,
        )
    }

    single<DiagnosticLogRepository> {
        DiagnosticLogRepositoryImpl(
            sink = get(),
            dispatcher = get<CoroutineDispatcher>(KoinQualifiers.LogWriter),
        )
    }

    // Reusable handler that funnels escaped coroutine exceptions into the crash record path.
    // Opt-in per scope (e.g. the main-thread watchdog scope) — not installed on ApplicationScope,
    // whose handler lives in core:common and cannot depend on :core:logging.
    single<CoroutineExceptionHandler> { diagnosticCoroutineExceptionHandler(get()) }
}
