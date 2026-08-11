package com.garfiec.librechat.shared

import com.garfiec.librechat.core.data.datastore.ServerDataStore
import com.garfiec.librechat.core.data.datastore.SettingsDataStore
import com.garfiec.librechat.core.common.lifecycle.DeferredWorkWindow
import com.garfiec.librechat.core.data.prefetch.PrefetchBackgroundRunner
import com.garfiec.librechat.core.data.prefetch.PrefetchController
import com.garfiec.librechat.core.data.prefetch.PrefetchRunOutcome
import com.garfiec.librechat.core.data.prefetch.PrefetchScheduler
import com.garfiec.librechat.core.data.repository.AuthRepository
import com.garfiec.librechat.core.data.repository.ConfigRepository
import com.garfiec.librechat.core.data.repository.FileRepository
import com.garfiec.librechat.core.model.ModelRef
import com.garfiec.librechat.feature.chat.navigation.ModelShortcutBus
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.koin.core.Koin
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Swift-callable accessors for Koin-managed singletons.
 * The Koin instance is stored here by startIosKoin() and used
 * by Swift via typed helper functions.
 *
 * All functions use @Throws to ensure Kotlin exceptions propagate
 * as NSError to Swift rather than causing SIGABRT via
 * trapOnUndeclaredException.
 */
object IosKoinAccessor {

    internal lateinit var koin: Koin

    @Throws(Exception::class)
    fun getSDK(): LibreChatSDK = koin.get()

    @Throws(Exception::class)
    fun getServerDataStore(): ServerDataStore = koin.get()

    @Throws(Exception::class)
    fun getAuthRepository(): AuthRepository = koin.get()

    @Throws(Exception::class)
    fun getFileRepository(): FileRepository = koin.get()

    @Throws(Exception::class)
    fun getConfigRepository(): ConfigRepository = koin.get()

    /**
     * Snapshot of the account's most-used models, for the Swift layer to publish as home-screen
     * quick actions (typically read when the app backgrounds). Suspends until the account resolves
     * (the flow emits nothing while warming), which is already the case by background time.
     */
    @Throws(Exception::class)
    suspend fun currentTopModels(limit: Int): List<ModelRef> =
        koin.get<SettingsDataStore>().topUsedModels(limit).first()

    /** Routes a tapped quick action into the shared navigation host — opens a new chat on the model. */
    @Throws(Exception::class)
    fun requestModelShortcut(endpoint: String, model: String) {
        koin.get<ModelShortcutBus>().request(endpoint, model)
    }

    /**
     * Serializes background runs. iOS may launch both task types into the same process, and two
     * runners would interleave badly: the second's start handshake latches onto the pass the first is
     * already waiting on, waits it out, and records that result as its own — overwriting the first's
     * entry in the one figure on the readout that is recorded rather than derived.
     */
    private val backgroundRunLock = Mutex()

    /**
     * Runs one prefetch pass for a `BGTask` handler and queues the next occurrence, returning whether
     * it reached a verdict — which is what the handler reports to `setTaskCompleted`.
     *
     * iOS drops a task request once it launches the task, so something has to queue the next one or
     * the feature fires exactly once. The coordinator cannot be that something on its own: it acts
     * only when its decision *changes*, so in a process that was already running with prefetching on
     * it stays silent.
     */
    @Throws(Exception::class)
    suspend fun runBackgroundPrefetch(budgetSeconds: Double): Boolean {
        // A second concurrent launch has nothing to do — the first run's reschedule re-queues both
        // identifiers — so it reports success rather than starting a competing pass.
        if (!backgroundRunLock.tryLock()) return true
        try {
            var outcome = PrefetchRunOutcome.INTERRUPTED
            try {
                outcome = koin.get<PrefetchBackgroundRunner>().runOnce(budgetSeconds.seconds)
                return outcome != PrefetchRunOutcome.INTERRUPTED
            } finally {
                // On the way out however we leave, and uncancellable. Expiration cancels this
                // coroutine, and by then iOS has already consumed the request that launched us:
                // skipping the re-queue on that path is what would take the feature off the air for
                // good rather than merely ending this run early.
                withContext(NonCancellable) { rescheduleAfter(outcome) }
            }
        } finally {
            backgroundRunLock.unlock()
        }
    }

    /**
     * Queues the next occurrence, unless this run found the conditions the coordinator schedules on
     * to be unmet.
     *
     * Deferring to the runner's own verdict rather than re-reading the settings keeps one rule
     * instead of a second copy that can drift from [PrefetchScheduleCoordinator]'s. Re-queueing on
     * either verdict would resurrect work the coordinator has just cancelled — and because
     * `prefetchEnabled` is global rather than account-scoped, nothing would ever cancel it again, so
     * a signed-out device would keep waking every few hours indefinitely.
     */
    private suspend fun rescheduleAfter(outcome: PrefetchRunOutcome) {
        if (outcome == PrefetchRunOutcome.DISABLED || outcome == PrefetchRunOutcome.NO_SESSION) return
        koin.get<PrefetchScheduler>()
            .ensureScheduled(koin.get<SettingsDataStore>().prefetchOnMeteredEnabled.first())
    }

    /**
     * Whether a pass is running right now. Read as the app backgrounds, to decide whether taking a
     * background task assertion is worth it — taken unconditionally it would hold the whole app alive
     * for half a minute on every backgrounding, including for users with prefetching switched off,
     * and would keep an in-flight SSE stream on the `NWConnection` transport alive with it.
     */
    @Throws(Exception::class)
    fun isPrefetchPassInProgress(): Boolean = koin.get<PrefetchController>().passInProgress.value

    /**
     * Returns once no pass is running, so the caller can release its background task assertion.
     *
     * The settle delay is load-bearing. [PrefetchController] runs queued triggers through a single
     * collector, so the in-progress flag drops to false *between* two passes — a caller that released
     * on the first false would drop its assertion in that gap and let iOS suspend the app a second
     * into the run the user had just asked for.
     */
    @Throws(Exception::class)
    suspend fun awaitPrefetchPassEnd() {
        val controller = koin.get<PrefetchController>()
        while (true) {
            controller.passInProgress.first { !it }
            delay(PASS_SETTLE)
            if (!controller.passInProgress.value) return
        }
    }

    /**
     * Pins the deferred-work window open for exactly as long as the caller holds an OS background
     * assertion, so a pass can never outlive the thing keeping the process alive. Must be balanced by
     * [endPrefetchBackgroundRun].
     */
    @Throws(Exception::class)
    fun beginPrefetchBackgroundRun() {
        koin.get<DeferredWorkWindow>().beginBackgroundRun()
    }

    /** Releases the pin. Closing the window is what cancels a pass that is still going. */
    @Throws(Exception::class)
    fun endPrefetchBackgroundRun() {
        koin.get<DeferredWorkWindow>().endBackgroundRun()
    }

    private val PASS_SETTLE = 250.milliseconds
}
