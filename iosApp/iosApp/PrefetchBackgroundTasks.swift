import BackgroundTasks
import UIKit
import Shared

/// Bridges iOS background execution to the shared prefetcher.
///
/// Only what iOS will not let Kotlin do lives here: installing the launch handlers, which must happen
/// before launch finishes. Deciding *when* to schedule, submitting the requests, and running the pass
/// all stay on the Kotlin side so both platforms share one implementation.
enum PrefetchBackgroundTasks {

    /// Installs a launch handler per identifier.
    ///
    /// Must be called from `application(_:didFinishLaunchingWithOptions:)` — iOS refuses a
    /// registration made after launch completes. It touches only Kotlin constants, never Koin, so it
    /// does not care whether the graph has been started yet; each handler resolves Koin when it fires,
    /// by which point it certainly has.
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
        // The one leg of the identifier contract that can be checked from here. iOS refuses an
        // identifier missing from BGTaskSchedulerPermittedIdentifiers and refuses it quietly: the
        // handler is simply never called, and nothing else in the app would ever look wrong.
        assertionFailure("BGTaskScheduler refused \(identifier) — is it in BGTaskSchedulerPermittedIdentifiers?")
        NSLog("[W/Prefetch] BGTaskScheduler refused registration for \(identifier)")
    }

    private static func run(_ task: BGTask, budgetSeconds: Double) {
        // The holder is installed before the work starts so an expiration arriving immediately still
        // finds something to cancel. SKIE wraps bridged suspend calls in `withTaskCancellationHandler`,
        // so cancelling the Swift task does reach the Kotlin coroutine — but only through a retained
        // handle, and discarding it would quietly make expiration a no-op.
        let holder = CancellableWork()
        task.expirationHandler = { holder.cancel() }
        // On the main actor for two reasons: a launch handler registered with a nil queue runs on a
        // background queue, and startIosKoin's idempotence guard is not thread-safe; and it serializes
        // this against the app's own init(), which is the other caller.
        holder.work = Task { @MainActor in
            // A background-task launch connects no scene, so init() cannot be assumed to have run —
            // and if Koin were not up, every call below would throw into `try?` and report nothing.
            IosKoinHelperKt.startIosKoin()
            // `.boolValue` because a bridged suspend function returns its primitive boxed, and a
            // failure to reach Kotlin at all reads as "did not finish", which is the honest report.
            let reachedVerdict = (try? await IosKoinAccessor.shared
                .runBackgroundPrefetch(budgetSeconds: budgetSeconds))?.boolValue ?? false
            // Non-negotiable: iOS terminates the app for a task that never reports completion.
            task.setTaskCompleted(success: reachedVerdict)
        }
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
