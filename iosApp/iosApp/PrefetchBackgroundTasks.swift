import BackgroundTasks
import UIKit
import Shared

/// Bridges iOS background execution to the shared prefetcher.
///
/// Only the two things iOS will not let Kotlin do live here: installing the launch handlers, which
/// must happen before launch finishes, and taking a `UIApplication` background task assertion, which
/// needs the app object. Deciding *when* to schedule, submitting the requests, and running the pass
/// all stay on the Kotlin side so both platforms share one implementation.
enum PrefetchBackgroundTasks {

    /// Installs a launch handler per identifier.
    ///
    /// Must be called from `application(_:didFinishLaunchingWithOptions:)` — iOS refuses a
    /// registration made after launch completes. Touches only Kotlin constants, never Koin; each
    /// handler resolves Koin when it fires.
    static func register() {
        register(
            identifier: IosPrefetchTasks.shared.REFRESH_ID,
            budgetSeconds: IosPrefetchTasks.shared.REFRESH_BUDGET_SECONDS
        )
        register(
            identifier: IosPrefetchTasks.shared.PROCESSING_ID,
            budgetSeconds: IosPrefetchTasks.shared.PROCESSING_BUDGET_SECONDS
        )
    }

    private static func register(identifier: String, budgetSeconds: Double) {
        let registered = BGTaskScheduler.shared.register(
            forTaskWithIdentifier: identifier,
            using: nil
        ) { task in
            run(task, budgetSeconds: budgetSeconds)
        }
        guard !registered else { return }
        assertionFailure("BGTaskScheduler refused \(identifier) — is it in BGTaskSchedulerPermittedIdentifiers?")
        NSLog("[W/Prefetch] BGTaskScheduler refused registration for \(identifier)")
    }

    private static func run(_ task: BGTask, budgetSeconds: Double) {
        // Installed before the work starts so an immediate expiration still finds something to cancel.
        // SKIE wraps bridged suspend calls in `withTaskCancellationHandler`, so cancelling the Swift
        // task does reach the Kotlin coroutine — but only through a retained handle.
        let holder = CancellableWork()
        task.expirationHandler = { holder.cancel() }
        // Main actor: a handler registered with a nil queue runs on a background queue, startIosKoin's
        // idempotence guard is not thread-safe, and this serializes against the app's own init().
        holder.work = Task { @MainActor in
            // A background-task launch connects no scene, so init() cannot be assumed to have run —
            // and with Koin down every call below would throw into `try?` and report nothing.
            IosKoinHelperKt.startIosKoin()
            // `.boolValue`: a bridged suspend function returns its primitive boxed. Failing to reach
            // Kotlin at all reads as "did not finish".
            let reachedVerdict = (try? await IosKoinAccessor.shared
                .runBackgroundPrefetch(budgetSeconds: budgetSeconds))?.boolValue ?? false
            // Non-negotiable: iOS terminates the app for a task that never reports completion.
            task.setTaskCompleted(success: reachedVerdict)
        }
    }

    /// Keeps the process alive for a pass that is still running as the app leaves the screen.
    ///
    /// Conditional on a pass actually being in flight: taken on every backgrounding it would hold the
    /// whole app up for around half a minute for every user, including those with prefetching off, and
    /// keep an in-flight SSE stream alive with it.
    ///
    /// The Kotlin-side background run is opened and closed with the assertion, never independently, so
    /// releasing here cancels the pass instead of leaving it frozen mid-request. Check for a pass
    /// *before* opening the run: opening it first reopens a window that had already closed, and the
    /// gate's rising edge would start a fresh pass at the worst possible moment.
    @MainActor
    static func holdIfPassRunning() {
        guard !assertion.isHeld,
              (try? IosKoinAccessor.shared.isPrefetchPassInProgress()) == true else { return }

        try? IosKoinAccessor.shared.beginPrefetchBackgroundRun()
        let held = assertion.begin {
            // iOS reclaims the assertion after roughly 30 seconds and calls this synchronously on the
            // main thread. Release inline rather than through a Task: an enqueued release can sit
            // behind the very pass this is holding up, and arriving late means termination.
            releaseHold()
        }
        guard held else {
            // iOS refused, so nothing is keeping the process up. Unpin now — every later release goes
            // through `assertion.end()`, which would find nothing to do and leave the window open for
            // the life of the process.
            try? IosKoinAccessor.shared.endPrefetchBackgroundRun()
            return
        }
        Task { @MainActor in
            try? await IosKoinAccessor.shared.awaitPrefetchPassEnd()
            releaseHold()
        }
    }

    private static let assertion = BackgroundAssertion()

    /// Ends the assertion and the background run together. Safe to call twice — whichever of the
    /// expiration handler and the pass-finished path arrives second does nothing.
    private static func releaseHold() {
        guard assertion.end() else { return }
        try? IosKoinAccessor.shared.endPrefetchBackgroundRun()
    }
}

/// A `UIApplication` background task assertion that can be released from any thread.
///
/// Guarded rather than main-actor-isolated so the expiration handler can end it inline: hopping to
/// an actor to release would reintroduce the delay the handler exists to avoid.
private final class BackgroundAssertion: @unchecked Sendable {

    private let lock = NSLock()
    private var id: UIBackgroundTaskIdentifier = .invalid

    var isHeld: Bool {
        lock.lock()
        defer { lock.unlock() }
        return id != .invalid
    }

    /// Returns false when iOS declined to grant one, which the caller has to treat as "nothing is
    /// holding this process up" rather than as a held assertion.
    func begin(onExpire: @escaping () -> Void) -> Bool {
        let taskId = UIApplication.shared.beginBackgroundTask(
            withName: "prefetch-tail",
            expirationHandler: onExpire
        )
        guard taskId != .invalid else { return false }
        lock.lock()
        defer { lock.unlock() }
        id = taskId
        return true
    }

    /// Ends the assertion, returning true only for the caller that actually ended it — so two
    /// racing releases cannot both unwind the Kotlin-side background run and drive its counter
    /// negative.
    func end() -> Bool {
        lock.lock()
        let taskId = id
        id = .invalid
        lock.unlock()

        guard taskId != .invalid else { return false }
        UIApplication.shared.endBackgroundTask(taskId)
        return true
    }
}

/// Holds a `Task` that a different thread may need to cancel.
///
/// `expirationHandler` fires on the system's own queue, so the handle is guarded rather than merely
/// stored — and a cancellation that lands before the work is assigned is remembered, so it cannot be
/// lost in the gap between installing the handler and starting the task.
private final class CancellableWork: @unchecked Sendable {

    private let lock = NSLock()
    private var task: Task<Void, Never>?
    private var isCancelled = false

    var work: Task<Void, Never>? {
        get {
            lock.lock()
            defer { lock.unlock() }
            return task
        }
        set {
            lock.lock()
            defer { lock.unlock() }
            task = newValue
            if isCancelled { newValue?.cancel() }
        }
    }

    func cancel() {
        lock.lock()
        defer { lock.unlock() }
        isCancelled = true
        task?.cancel()
    }
}
