package com.garfiec.librechat.core.logging

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.time.TimeSource

/**
 * Conservative main-thread responsiveness watchdog (a soft ANR detector).
 *
 * Each [pollInterval], if there is no ping already in flight, it opens one (records "now") and posts
 * a clear-task to [Dispatchers.Main]. When the main thread is healthy that task runs promptly and
 * clears the ping. If a ping stays outstanding longer than [threshold], the main thread is stalled
 * and we emit ONE warning record; we then debounce until the main thread recovers (the clear-task
 * finally runs), so a long stall yields a single record, not a flood.
 *
 * It owns its OWN supervised scope on [Dispatchers.Default] so a failure can never tear down the
 * caller's (application) scope. It is best-effort observability only: it does NOT kill the app, dump
 * threads, or interrupt anything, runs entirely off the main thread (no busy-loop), and every step
 * is wrapped so a logging failure or cancellation can never crash the host process.
 */
private const val DEFAULT_POLL_MS = 1_000L
private const val DEFAULT_THRESHOLD_MS = 5_000L

class MainThreadWatchdog(
    private val pollInterval: Long = DEFAULT_POLL_MS,
    private val threshold: Long = DEFAULT_THRESHOLD_MS,
) {
    // Monotonic clock so wall-clock changes (NTP, DST) can't produce a phantom stall.
    private val clock = TimeSource.Monotonic

    // Mark of the in-flight ping, or null when the main thread has acknowledged and caught up.
    // Only one ping is ever outstanding at a time, so the mark isn't reset every tick.
    @Volatile
    private var pingInFlightSince: TimeSource.Monotonic.ValueTimeMark? = null

    // True once the current stall has been reported; re-armed when a fresh ping is opened.
    @Volatile
    private var reported = false

    /**
     * Starts the watchdog on a fresh supervised [Dispatchers.Default] scope (isolated from the
     * caller). An optional [exceptionHandler] funnels any escaped failure into the crash-record path.
     * Returns the [Job] so the caller can cancel it; safe to ignore (lives for the process).
     */
    fun start(exceptionHandler: CoroutineExceptionHandler? = null): Job {
        val scope = CoroutineScope(
            Dispatchers.Default + SupervisorJob() + (exceptionHandler ?: EmptyCoroutineContext),
        )
        return scope.launch {
            while (isActive) {
                runCatching {
                    val inFlight = pingInFlightSince
                    if (inFlight == null) {
                        // No ping outstanding: open one, re-arm reporting, and ask the main thread to ack.
                        pingInFlightSince = clock.markNow()
                        reported = false
                        scope.launch(Dispatchers.Main) { pingInFlightSince = null }
                    } else if (!reported) {
                        // Previous ping still unacknowledged → the main thread hasn't run our task.
                        val blockedMs = inFlight.elapsedNow().inWholeMilliseconds
                        if (blockedMs >= threshold) {
                            reported = true
                            emitStall(blockedMs)
                        }
                    }
                }
                delay(pollInterval)
            }
        }
    }

    private fun emitStall(blockedMs: Long) {
        runCatching {
            Diag.w(
                tag = "ANR",
                origin = LogOrigin.CLIENT,
                attrs = mapOf("blockedMs" to blockedMs.toString()),
            ) { "main thread unresponsive" }
        }
    }
}

/**
 * Convenience entry point: starts a [MainThreadWatchdog] with default thresholds on its own isolated
 * supervised scope and returns its [Job]. Call once after Koin/logging are initialized.
 */
fun startMainThreadWatchdog(exceptionHandler: CoroutineExceptionHandler? = null): Job =
    MainThreadWatchdog().start(exceptionHandler)
