package com.garfiec.librechat.feature.settings.state

import com.garfiec.librechat.core.data.prefetch.PrefetchConditions
import com.garfiec.librechat.core.data.prefetch.PrefetchConversationStatus
import com.garfiec.librechat.core.data.prefetch.PrefetchRunState
import com.garfiec.librechat.core.data.prefetch.PrefetchStatus
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Precedence is the whole content of the status reducer, and every case here is one a user would
 * otherwise be shown a misleading answer for.
 */
class PrefetchStatusDisplayTest {

    private fun status(
        conditions: PrefetchConditions = allMet,
        runState: PrefetchRunState = PrefetchRunState.Idle,
        pending: List<PrefetchConversationStatus> = emptyList(),
    ) = PrefetchStatus.Empty.copy(conditions = conditions, runState = runState, pending = pending)

    @Test
    fun `a met gate with nothing stale reads as up to date`() {
        assertThat(status().toDisplayStatus()).isEqualTo(PrefetchDisplayStatus.UpToDate)
    }

    /**
     * The state the manual run exists for: passes start on a rising edge of the gate, so work that
     * goes stale while the gate is already open has nothing to re-trigger it.
     */
    @Test
    fun `a met gate with outstanding work reads as waiting, not up to date`() {
        val result = status(pending = listOf(pendingRow, pendingRow.copy(conversationId = "b")))

        assertThat(result.toDisplayStatus()).isEqualTo(PrefetchDisplayStatus.Waiting(pending = 2))
    }

    @Test
    fun `warming reports its progress`() {
        val result = status(runState = PrefetchRunState.WarmingMessages(completed = 2, total = 7))

        assertThat(result.toDisplayStatus()).isEqualTo(PrefetchDisplayStatus.Warming(2, 7))
    }

    /**
     * The gate closing is the ordinary way a pass ends, so a pass still unwinding must not be
     * reported as paused by the very condition that is cancelling it — that reads as a stall.
     */
    @Test
    fun `an in-flight pass outranks an unmet condition`() {
        val result = status(
            conditions = allMet.copy(appIdle = false),
            runState = PrefetchRunState.WarmingMessages(completed = 1, total = 3),
        )

        assertThat(result.toDisplayStatus()).isEqualTo(PrefetchDisplayStatus.Warming(1, 3))
    }

    /** The breaker persists on its own, so nothing short of being switched off may mask it. */
    @Test
    fun `the breaker outranks every condition except being switched off`() {
        val result = status(
            conditions = allMet.copy(networkAllowed = false),
            runState = PrefetchRunState.Stopped,
        )

        assertThat(result.toDisplayStatus()).isEqualTo(PrefetchDisplayStatus.Stopped)
    }

    /**
     * The breaker is cleared only by the manual run, which lives behind an entry point that is
     * itself disabled while prefetching is off. Reporting a stale failure over "Off" would leave a
     * permanent error banner for a feature the user turned off and no in-app way to clear it.
     */
    @Test
    fun `switched off outranks the breaker`() {
        val result = status(
            conditions = allMet.copy(enabled = false),
            runState = PrefetchRunState.Stopped,
        )

        assertThat(result.toDisplayStatus()).isEqualTo(PrefetchDisplayStatus.Off)
    }

    @Test
    fun `switched off outranks any pause reason`() {
        val result = status(conditions = allMet.copy(enabled = false, networkAllowed = false))

        assertThat(result.toDisplayStatus()).isEqualTo(PrefetchDisplayStatus.Off)
    }

    /**
     * An unmetered check answers false for a device with no radio exactly as for one on mobile data,
     * so collapsing them tells a user in airplane mode to wait for Wi-Fi.
     */
    @Test
    fun `being offline is reported as offline, not as metered`() {
        val offline = allMet.copy(connected = false, networkAllowed = false)

        assertThat(offline.pauseReason()).isEqualTo(PrefetchPauseReason.OFFLINE)
        assertThat(status(conditions = offline).toDisplayStatus())
            .isEqualTo(PrefetchDisplayStatus.Paused(PrefetchPauseReason.OFFLINE))
    }

    /** With the metered override on, an offline device passes the network check but cannot work. */
    @Test
    fun `the metered override does not hide being offline`() {
        val offline = allMet.copy(connected = false, networkAllowed = true)

        assertThat(offline.pauseReason()).isEqualTo(PrefetchPauseReason.OFFLINE)
    }

    /**
     * Network before power before busy: that is the order the user can act on, and "app is busy"
     * resolves by itself a moment later, so reporting it over "waiting for Wi-Fi" would send them
     * looking for the wrong problem.
     */
    @Test
    fun `pause reasons are reported in the order the user can act on them`() {
        val everythingUnmet = PrefetchConditions(
            enabled = true,
            foreground = false,
            networkAllowed = false,
            powerAvailable = false,
            appIdle = false,
            connected = true,
        )

        assertThat(everythingUnmet.pauseReason()).isEqualTo(PrefetchPauseReason.NETWORK)
        assertThat(everythingUnmet.copy(networkAllowed = true).pauseReason())
            .isEqualTo(PrefetchPauseReason.POWER)
        assertThat(everythingUnmet.copy(networkAllowed = true, powerAvailable = true).pauseReason())
            .isEqualTo(PrefetchPauseReason.BACKGROUND)
        assertThat(
            everythingUnmet.copy(networkAllowed = true, powerAvailable = true, foreground = true)
                .pauseReason(),
        ).isEqualTo(PrefetchPauseReason.BUSY)
    }

    @Test
    fun `an open gate has no pause reason`() {
        assertThat(allMet.pauseReason()).isNull()
    }

    /** Outstanding work must not be reported as waiting when a condition is blocking it. */
    @Test
    fun `an unmet condition outranks outstanding work`() {
        val result = status(
            conditions = allMet.copy(networkAllowed = false),
            pending = listOf(pendingRow),
        )

        assertThat(result.toDisplayStatus())
            .isEqualTo(PrefetchDisplayStatus.Paused(PrefetchPauseReason.NETWORK))
    }

    private companion object {
        val allMet = PrefetchConditions(
            enabled = true,
            foreground = true,
            networkAllowed = true,
            powerAvailable = true,
            appIdle = true,
            connected = true,
        )
        val pendingRow = PrefetchConversationStatus(
            conversationId = "a",
            title = "Project notes",
            pinned = false,
            warmedAt = null,
            isCurrent = false,
        )
    }
}
