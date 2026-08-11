package com.garfiec.librechat.core.data.prefetch

import com.garfiec.librechat.core.logging.Diag
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.BackgroundTasks.BGAppRefreshTaskRequest
import platform.BackgroundTasks.BGProcessingTaskRequest
import platform.BackgroundTasks.BGTaskRequest
import platform.BackgroundTasks.BGTaskScheduler
import platform.BackgroundTasks.BGTaskSchedulerErrorCodeUnavailable
import platform.Foundation.NSDate
import platform.Foundation.NSError
import platform.Foundation.NSUserDefaults
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.timeIntervalSince1970

/**
 * The two background task identifiers, and what each is for.
 *
 * These are a four-way contract: this object submits them, `PrefetchBackgroundTasks.swift` registers
 * a handler for each, `Info.plist` permits them under `BGTaskSchedulerPermittedIdentifiers`, and iOS
 * matches all three by string. A mismatch is not a build error — the handler simply never runs, and
 * `BGTaskScheduler` reports nothing wrong. Registration has to live in Swift because handlers must be
 * installed before launch finishes, which is a moment no Kotlin object can be sure of reaching.
 */
object IosPrefetchTasks {

    /**
     * Opportunistic top-up. iOS decides when, on its own model of when the app gets used; there is no
     * way to require power or a network on a refresh task, so the prefetch gate is the only thing
     * holding one back.
     */
    const val REFRESH_ID: String = "com.garfiec.librechat.prefetch.refresh"

    /** The bulk overnight pass — the direct counterpart of the Android periodic job. */
    const val PROCESSING_ID: String = "com.garfiec.librechat.prefetch.processing"

    /**
     * Refresh tasks are held to roughly 30 seconds of wall clock, and this is the *whole* run, not
     * just the warming: [PrefetchBackgroundRunner] arms its deadline before waiting on the session or
     * on a pass to start, so the handshake is spent inside this figure rather than before it. The
     * remainder is headroom for the pass to unwind and record its outcome, because overrunning is not
     * a soft failure — iOS kills the app for missing `setTaskCompleted`.
     */
    const val REFRESH_BUDGET_SECONDS: Double = 22.0

    /** Processing tasks get minutes. Sized like the Android worker's budget, for the same reasons. */
    const val PROCESSING_BUDGET_SECONDS: Double = 300.0

    /** Earliest the system may consider each task. A floor, never a cadence — see [IosPrefetchScheduler]. */
    const val REFRESH_EARLIEST_SECONDS: Double = 2.0 * 60 * 60
    const val PROCESSING_EARLIEST_SECONDS: Double = 6.0 * 60 * 60
}

/**
 * Asks `BGTaskScheduler` to wake the app for a prefetch pass.
 *
 * Submission and cancellation live here rather than in Swift so that [PrefetchScheduleCoordinator]
 * drives both platforms — same decisions, same tests, and cancel-on-logout for free. Only the launch
 * handlers are Swift's, because only the app target can install them in time.
 */
class IosPrefetchScheduler : PrefetchScheduler {

    private val scheduler get() = BGTaskScheduler.sharedScheduler
    private val defaults get() = NSUserDefaults.standardUserDefaults

    /**
     * Whether iOS has last told us it will run background work at all.
     *
     * Starts optimistic and is cleared only on a refusal that says otherwise, because the readout
     * hides the scheduled-run row when this is false and a row absent for a recoverable reason is
     * worse than one that is briefly wrong. A plain field: a stale read costs one status emission,
     * and every writer is a submission that just happened.
     */
    private var schedulingAvailable = true

    override val isSupported: Boolean get() = schedulingAvailable

    /**
     * The metered setting is deliberately ignored here.
     *
     * `BGProcessingTaskRequest.requiresNetworkConnectivity` can only say "wait for *a* network" — iOS
     * exposes no Wi-Fi-versus-cellular constraint the way `NetworkType.UNMETERED` does. So on this
     * platform the choice is enforced where it can be: the gate re-checks it when the task fires and
     * declines the pass. That is also why this needs no re-registration when the setting changes,
     * unlike its Android counterpart, where the constraint is baked into the job at enqueue time.
     */
    override fun ensureScheduled(allowMetered: Boolean) {
        submitDue(IosPrefetchTasks.REFRESH_ID, IosPrefetchTasks.REFRESH_EARLIEST_SECONDS) {
            BGAppRefreshTaskRequest(it)
        }
        submitDue(IosPrefetchTasks.PROCESSING_ID, IosPrefetchTasks.PROCESSING_EARLIEST_SECONDS) {
            BGProcessingTaskRequest(it).apply {
                // Both default to false. Left alone, iOS would launch this off-charger with no
                // network, the gate would never open, and the budget would burn reporting nothing —
                // the settled charging-plus-network decision has to be stated.
                requiresExternalPower = true
                requiresNetworkConnectivity = true
            }
        }
    }

    override fun cancel() {
        for (identifier in IDENTIFIERS) {
            scheduler.cancelTaskRequestWithIdentifier(identifier)
            // Drop the due date too, so re-enabling later starts a fresh interval rather than
            // inheriting one set before the user switched the feature off.
            defaults.removeObjectForKey(dueKey(identifier))
        }
    }

    /**
     * Submits [identifier] for its stored due date, choosing a new one only once the old has passed.
     *
     * The stored date is what makes this idempotent, and it has to be persisted because the thing it
     * defends against spans process death. Recomputing `now + interval` on every call would push the
     * date back out at each process start, and since this runs at every launch, a user who opens the
     * app daily would keep resetting the clock and neither task would ever mature — the same trap the
     * Android side avoids with `KEEP`.
     *
     * Doing it this way rather than by querying `getPendingTaskRequests` is deliberate: that call is
     * asynchronous, which would leave the submission racing both `cancel()` and, at the end of a
     * background run, the app's own suspension.
     */
    private fun submitDue(identifier: String, interval: Double, build: (String) -> BGTaskRequest) {
        val now = NSDate().timeIntervalSince1970
        val stored = defaults.doubleForKey(dueKey(identifier))
        val due = if (stored > now) stored else now + interval
        val request = build(identifier).apply {
            earliestBeginDate = NSDate.dateWithTimeIntervalSince1970(due)
        }
        if (submit(request)) {
            defaults.setDouble(due, dueKey(identifier))
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun submit(request: BGTaskRequest): Boolean = memScoped {
        val error = alloc<ObjCObjectVar<NSError?>>()
        if (scheduler.submitTaskRequest(request, error.ptr)) {
            schedulingAvailable = true
            return@memScoped true
        }

        val code = error.value?.code
        if (code == BGTaskSchedulerErrorCodeUnavailable) {
            // Background App Refresh is switched off for this app, so nothing will ever run. Say so,
            // rather than leaving the settings screen showing a scheduled-run row that reads "Never"
            // forever beside an all-green conditions checklist naming no cause.
            schedulingAvailable = false
        }
        // Worth a log line either way: a refusal is otherwise completely silent.
        Diag.w(
            "Prefetch",
            attrs = mapOf(
                "identifier" to request.identifier,
                "code" to (code?.toString() ?: "unknown"),
            ),
        ) { "background task submission refused" }
        false
    }

    private fun dueKey(identifier: String) = "$DUE_KEY_PREFIX$identifier"

    private companion object {
        val IDENTIFIERS = listOf(IosPrefetchTasks.REFRESH_ID, IosPrefetchTasks.PROCESSING_ID)
        const val DUE_KEY_PREFIX = "prefetch_task_due_"
    }
}
