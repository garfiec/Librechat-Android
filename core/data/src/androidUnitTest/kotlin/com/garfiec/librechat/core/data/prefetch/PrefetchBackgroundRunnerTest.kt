package com.garfiec.librechat.core.data.prefetch

import com.garfiec.librechat.core.common.identity.AccountId
import com.garfiec.librechat.core.common.identity.Session
import com.garfiec.librechat.core.common.identity.SessionManager
import com.garfiec.librechat.core.common.lifecycle.BackgroundWorkSupport
import com.garfiec.librechat.core.common.lifecycle.DeferredWorkWindow
import com.garfiec.librechat.core.common.lifecycle.ForegroundSignal
import com.garfiec.librechat.core.data.datastore.SettingsDataStore
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.time.AbstractLongTimeSource
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit
import kotlin.time.TimeSource

/**
 * The runner's whole job is to wait on someone else's pass without starting a second one, and every
 * way that goes wrong is silent: a duplicated pass looks like ordinary traffic, and a missed one
 * looks like an unmet constraint. None of it is observable from the outcome alone, so these tests
 * drive the pass signal directly.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PrefetchBackgroundRunnerTest {

    private val account = AccountId("srv:user-a")

    private val passInProgress = MutableStateFlow(false)
    private val completedPasses = MutableStateFlow(0)
    private val enabled = MutableStateFlow(true)

    private val controller = mockk<PrefetchController>(relaxed = true)
    private val engine = mockk<PrefetchEngine>()
    private val settingsDataStore = mockk<SettingsDataStore>(relaxed = true)

    private val window = DeferredWorkWindow(
        foregroundSignal = ForegroundSignal(),
        support = BackgroundWorkSupport.SUPPORTED,
    )

    private fun TestScope.runner(hasSession: Boolean = true): PrefetchBackgroundRunner {
        every { controller.passInProgress } returns passInProgress
        // A relaxed mock hands back an Object for this, which blows up on the Int read.
        every { controller.completedPasses } returns completedPasses
        every { settingsDataStore.prefetchEnabled } returns enabled
        every { engine.runState } returns
            MutableStateFlow(PrefetchAccountRunState(account.value, PrefetchRunState.Idle))

        // Mocked rather than constructed: Session's constructor is internal to :core:common.
        val session = mockk<Session>()
        every { session.accountId } returns account
        val sessionManager = mockk<SessionManager>()
        every { sessionManager.current } returns MutableStateFlow(session.takeIf { hasSession })

        return PrefetchBackgroundRunner(
            sessionManager = sessionManager,
            controller = controller,
            engine = engine,
            deferredWorkWindow = window,
            settingsDataStore = settingsDataStore,
            nowMillis = { currentTimeMillis() },
            // Must read the same clock the timeouts do: on the default monotonic source the budget
            // runs on wall time while every wait runs on virtual time, so no budget assertion holds.
            timeSource = virtualTimeSource(),
        )
    }

    private fun TestScope.currentTimeMillis(): Long = testScheduler.currentTime

    private fun TestScope.virtualTimeSource(): TimeSource =
        object : AbstractLongTimeSource(DurationUnit.MILLISECONDS) {
            override fun read(): Long = testScheduler.currentTime
        }

    @Test
    fun `prefetching switched off is reported without touching the controller`() = runTest {
        enabled.value = false

        assertThat(runner().runOnce(BUDGET)).isEqualTo(PrefetchRunOutcome.DISABLED)
    }

    /**
     * The case that matters most. Opening the window is itself a pass trigger, so in a process the
     * scheduler spawned the rising edge starts a pass with nobody asking. Requesting one as well
     * would run two full passes back to back.
     */
    @Test
    fun `a pass started by the window opening is not asked for again`() = runTest {
        val runner = runner()
        val result = async { runner.runOnce(BUDGET) }

        // The gate's rising edge, standing in for the controller's own collector.
        advanceTimeBy(100)
        passInProgress.value = true
        advanceTimeBy(500)
        passInProgress.value = false
        advanceUntilIdle()

        assertThat(result.await()).isEqualTo(PrefetchRunOutcome.COMPLETED)
        verify(exactly = 0) { controller.requestScheduledRun() }
    }

    /**
     * The other half: an already-running process with the gate open has no edge to ride, so nothing
     * starts on its own and the runner has to ask. Without this the job burns its budget waiting.
     */
    @Test
    fun `a run is requested when nothing starts on its own`() = runTest {
        every { controller.requestScheduledRun() } answers { passInProgress.value = true }

        val runner = runner()
        val result = async { runner.runOnce(BUDGET) }

        // Past the grace it waits for a pass to appear on its own, at which point it asks — and the
        // stub above answers by starting one. Stepping rather than idling, because idling would run
        // the budget out too and report the pass as overrunning instead of completing.
        advanceTimeBy(6.seconds)
        passInProgress.value = false
        advanceUntilIdle()

        assertThat(result.await()).isEqualTo(PrefetchRunOutcome.COMPLETED)
        verify(exactly = 1) { controller.requestScheduledRun() }
    }

    /** A gate that never opens must report, not hang until the platform kills the job. */
    @Test
    fun `a gate that never opens is reported as constraints unmet`() = runTest {
        val runner = runner()
        val result = async { runner.runOnce(BUDGET) }

        advanceUntilIdle()

        assertThat(result.await()).isEqualTo(PrefetchRunOutcome.CONSTRAINTS_UNMET)
    }

    /** A pass still running when the budget ends is not an error — watermarks make it resumable. */
    @Test
    fun `a pass outlasting the budget is reported as budget expired`() = runTest {
        val runner = runner()
        val result = async { runner.runOnce(BUDGET) }

        advanceTimeBy(100)
        passInProgress.value = true
        advanceUntilIdle()

        assertThat(result.await()).isEqualTo(PrefetchRunOutcome.BUDGET_EXPIRED)
    }

    /** Every run records, including the ones that warmed nothing — those are the invisible ones. */
    @Test
    fun `the outcome is recorded`() = runTest {
        val recorded = slot<ScheduledRunRecord>()
        coEvery { settingsDataStore.recordScheduledRun(any(), capture(recorded)) } returns Unit

        runner().runOnce(BUDGET)
        advanceUntilIdle()

        assertThat(recorded.captured.outcome).isEqualTo(PrefetchRunOutcome.CONSTRAINTS_UNMET)
    }

    /** The window must close however the run ended, or the gate stays open on a dead run forever. */
    @Test
    fun `the window is closed when the run ends`() = runTest {
        runner().runOnce(BUDGET)
        advanceUntilIdle()

        // Nothing marked the UI started, so the window is open only while a background run is.
        assertThat(window.isOpen.first()).isFalse()
    }

    /**
     * An iOS refresh task's whole allowance is ~30 seconds and the start graces alone are 10 of them,
     * so a budget armed only once a pass begins overruns by 3x — a kill, not a truncation.
     */
    @Test
    fun `a budget shorter than the start handshake still bounds the run`() = runTest {
        val runner = runner()
        val startedAt = testScheduler.currentTime

        val result = async { runner.runOnce(SHORT_BUDGET) }
        advanceUntilIdle()

        assertThat(result.await()).isEqualTo(PrefetchRunOutcome.CONSTRAINTS_UNMET)
        assertThat(testScheduler.currentTime - startedAt)
            .isAtMost(SHORT_BUDGET.inWholeMilliseconds)
    }

    @Test
    fun `waiting for a session cannot outlast the budget`() = runTest {
        val runner = runner(hasSession = false)
        val startedAt = testScheduler.currentTime

        val result = async { runner.runOnce(SHORT_BUDGET) }
        advanceUntilIdle()

        assertThat(result.await()).isEqualTo(PrefetchRunOutcome.NO_SESSION)
        assertThat(testScheduler.currentTime - startedAt)
            .isAtMost(SHORT_BUDGET.inWholeMilliseconds)
    }

    private companion object {
        val BUDGET = 2.minutes

        /** Shorter than SESSION_WAIT and than either start grace, so both have to be clamped. */
        val SHORT_BUDGET = 3.seconds
    }

    /**
     * WorkManager drops the worker the moment a constraint is lost, cancelling doWork. A cancelled
     * call never produces a return value, so a record written off the result would silently skip
     * exactly the runs the readout exists to explain.
     */
    @Test
    fun `a cancelled run still records an outcome`() = runTest {
        val recorded = slot<ScheduledRunRecord>()
        coEvery { settingsDataStore.recordScheduledRun(any(), capture(recorded)) } returns Unit

        val runner = runner()
        val job = launch { runner.runOnce(BUDGET) }
        advanceTimeBy(100)
        job.cancelAndJoin()

        assertThat(recorded.captured.outcome).isEqualTo(PrefetchRunOutcome.INTERRUPTED)
    }
}
